package cn.lucanet.enhancement.exit;

import com.sun.source.tree.*;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreeScanner;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Edits method text using javac positions without printing whole syntax trees. */
final class JavaMethodFormatting extends TreeScanner<Void,Void> {
    private static final Pattern LINE_MARGIN = Pattern.compile("(?:\\A|(?<=[\\r\\n]))[ \\t]*(?=[^ \\t\\r\\n])");
    private static final Pattern GUARD_START = Pattern.compile("\\h*(\\R)(\\h*)try \\{\\h*\\R\\h*");
    private static final Pattern GUARD_END = Pattern.compile(
            "\\}\\h*\\R\\h*finally \\{\\h*\\R\\h*CallStack\\.decStackLevel\\(\\);\\h*\\R\\h*\\}\\h*\\R(\\h*)");
    private final CompilationUnitTree unit;
    private final SourcePositions positions;
    private final String source;
    private final TreeMap<Integer,String> edits = new TreeMap<>();

    JavaMethodFormatting(CompilationUnitTree unit, SourcePositions positions, String source) {
        this.unit = unit;
        this.positions = positions;
        this.source = source;
    }

    private String between(int start, int end) {
        StringBuilder formatted = new StringBuilder(source.substring(start,end));
        for (Map.Entry<Integer,String> entry : edits.subMap(start,true,end,false).descendingMap().entrySet()) {
            int offset = entry.getKey();
            String operator = entry.getValue();
            formatted.replace(offset-start,operatorEnd(offset,operator)-start," "+operator+" ");
        }
        return formatted.toString();
    }

    String body(BlockTree block) {
        return body(start(block) + 1, end(block) - 1, block.getStatements());
    }

    private String body(int from, int to, List<? extends StatementTree> statements) {
        String formatted = between(from,to);
        Matcher lines = LINE_MARGIN.matcher(formatted);
        if (!lines.find() || lines.start() == 0) return formatted;
        int first = from + lines.end(), margin = lines.end() - lines.start();
        int laterMargin = Integer.MAX_VALUE;
        for (StatementTree statement : statements) {
            laterMargin = Math.min(laterMargin,lineIndent(start(statement),first));
            int last = end(statement) - 1;
            if (last > first && source.charAt(last) == '}') {
                laterMargin = Math.min(laterMargin,lineIndent(last,first));
            }
        }
        if (laterMargin == Integer.MAX_VALUE || laterMargin <= margin) return formatted;

        // LocalMethodPart.setBody trims the first line. Peer statements reveal any margin left on later lines.
        int surplus = laterMargin - margin;
        StringBuilder result = new StringBuilder(formatted.length());
        int kept = 0;
        while (lines.find()) {
            if (lines.end() - lines.start() < surplus) return formatted;
            result.append(formatted,kept,lines.start());
            kept = lines.start() + surplus;
        }
        return result.append(formatted,kept,formatted.length()).toString();
    }

    private int lineIndent(int offset, int first) {
        if (offset <= first) return Integer.MAX_VALUE;
        int line = Math.max(source.lastIndexOf('\n',offset-1),source.lastIndexOf('\r',offset-1)) + 1;
        return afterSpace(line) == offset ? offset - line : Integer.MAX_VALUE;
    }

    String withoutNativeGuard(MethodTree method, String className) {
        BlockTree body = method.getBody();
        if (body.getStatements().size() != 1 || !(body.getStatements().get(0) instanceof TryTree)) return null;
        TryTree guarded = (TryTree)body.getStatements().get(0);
        BlockTree work = guarded.getBlock();
        if (guarded.getFinallyBlock() == null || work.getStatements().isEmpty()) return null;

        // Match the exact generated header and footer. Comments or work outside them belong to the author.
        StatementTree enter = work.getStatements().get(0);
        Matcher prefix = GUARD_START.matcher(source).region(start(body) + 1, start(enter));
        Matcher suffix = GUARD_END.matcher(source).region(end(work) - 1, end(body) - 1);
        if (!prefix.matches() || !suffix.matches()) return null;
        String name = method.getReturnType() == null ? className : method.getName().toString();
        String expected = "CallStack.incAndCheckStackLevel(\"" + className + "." + name + "\");";
        if (!source.substring(start(enter), end(enter)).equals(expected)) return null;

        String lineBreak = prefix.group(1);
        List<? extends StatementTree> statements = work.getStatements();
        String inner = CustomJavaFormatting.nativeBody(body(end(enter),end(work)-1,statements.subList(1,statements.size())));
        String editor = CustomJavaFormatting.editorBody(inner, prefix.group(2));
        String closing = lineBreak + suffix.group(1);
        return editor.isEmpty() ? closing : lineBreak + editor.replace("\n", lineBreak) + closing;
    }

    @Override public Void visitBinary(BinaryTree tree, Void unused) {
        space(tree.getLeftOperand(),tree.getRightOperand(),tree.getKind());
        return super.visitBinary(tree,unused);
    }

    @Override public Void visitAssignment(AssignmentTree tree, Void unused) {
        space(tree.getVariable(),tree.getExpression(),Tree.Kind.ASSIGNMENT);
        return super.visitAssignment(tree,unused);
    }

    @Override public Void visitCompoundAssignment(CompoundAssignmentTree tree, Void unused) {
        space(tree.getVariable(),tree.getExpression(),tree.getKind());
        return super.visitCompoundAssignment(tree,unused);
    }

    @Override public Void visitVariable(VariableTree tree, Void unused) {
        if (tree.getInitializer()!=null) {
            int right = start(tree.getInitializer()), equals = beforeSpace(right)-1;
            if (equals >= 0 && source.charAt(equals)=='=') {
                int left = beforeSpace(equals);
                if (left > 0 && source.charAt(left-1)!='\r' && source.charAt(left-1)!='\n') {
                    edits.put(left,"=");
                }
            }
        }
        return super.visitVariable(tree,unused);
    }

    private void space(Tree left, Tree right, Tree.Kind kind) {
        String operator = operator(kind);
        int from = end(left), to = start(right);
        if (from>=0 && to>from && operatorEnd(from,operator)==to) edits.put(from,operator);
    }

    private int operatorEnd(int from, String operator) {
        int token = afterSpace(from);
        return source.startsWith(operator,token) ? afterSpace(token+operator.length()) : -1;
    }

    int start(Tree tree) { return (int)positions.getStartPosition(unit,tree); }

    int end(Tree tree) { return (int)positions.getEndPosition(unit,tree); }

    private int afterSpace(int offset) {
        while (offset<source.length() && horizontal(source.charAt(offset))) offset++;
        return offset;
    }

    private int beforeSpace(int offset) {
        while (offset>0 && horizontal(source.charAt(offset-1))) offset--;
        return offset;
    }

    private static boolean horizontal(char value) { return value==' ' || value=='\t'; }

    private static String operator(Tree.Kind kind) {
        switch (kind) {
            case ASSIGNMENT: return "=";
            case PLUS: return "+";
            case MINUS: return "-";
            case MULTIPLY: return "*";
            case DIVIDE: return "/";
            case REMAINDER: return "%";
            case LEFT_SHIFT: return "<<";
            case RIGHT_SHIFT: return ">>";
            case UNSIGNED_RIGHT_SHIFT: return ">>>";
            case LESS_THAN: return "<";
            case GREATER_THAN: return ">";
            case LESS_THAN_EQUAL: return "<=";
            case GREATER_THAN_EQUAL: return ">=";
            case EQUAL_TO: return "==";
            case NOT_EQUAL_TO: return "!=";
            case AND: return "&";
            case XOR: return "^";
            case OR: return "|";
            case CONDITIONAL_AND: return "&&";
            case CONDITIONAL_OR: return "||";
            case PLUS_ASSIGNMENT: return "+=";
            case MINUS_ASSIGNMENT: return "-=";
            case MULTIPLY_ASSIGNMENT: return "*=";
            case DIVIDE_ASSIGNMENT: return "/=";
            case REMAINDER_ASSIGNMENT: return "%=";
            case LEFT_SHIFT_ASSIGNMENT: return "<<=";
            case RIGHT_SHIFT_ASSIGNMENT: return ">>=";
            case UNSIGNED_RIGHT_SHIFT_ASSIGNMENT: return ">>>=";
            case AND_ASSIGNMENT: return "&=";
            case XOR_ASSIGNMENT: return "^=";
            case OR_ASSIGNMENT: return "|=";
            default: throw new IllegalArgumentException("Unexpected Java operator: "+kind);
        }
    }
}
