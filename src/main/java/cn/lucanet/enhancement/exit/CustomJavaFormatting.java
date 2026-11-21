package cn.lucanet.enhancement.exit;

import java.util.StringJoiner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class CustomJavaFormatting {
    private static final String METHODS = "={30}  Methods  ={30}";
    private static final Pattern SECTION = Pattern.compile("(?m)^[ \\t]*// (?:"
            + "={29}  Variables  ={29}|" + METHODS + "|={22}  Task Execution Methods  ={23}"
            + "|={19}  Access to (?:Global Variables|Labels|Task parameters)  ={22}"
            + "|={27}  Inner Classes ={28}|={27}  End of Class  ={27,28})[ \\t]*(?:\\r\\n|\\r|\\n|\\z)");
    private static final Pattern METHOD_SECTION = Pattern.compile("(?m)^[ \\t]*// " + METHODS + "[ \\t]*(?:\\r\\n|\\r|\\n|\\z)");

    private CustomJavaFormatting() {}

    static String withoutSectionComments(String source) {
        return withoutSections(source, SECTION);
    }

    static boolean hasMethodSection(String source) {
        return !source.equals(withoutSections(source, METHOD_SECTION));
    }

    private static String withoutSections(String source, Pattern pattern) {
        Matcher sections = pattern.matcher(source);
        if (!sections.find()) return source;

        String lexicalSource = source.replace('\r', '\n');
        String code = JavaEditing.mask(lexicalSource);
        StringBuilder result = new StringBuilder(source.length());
        int depth = 0, scanned = 0, kept = 0;
        do {
            for (; scanned < sections.start(); scanned++) {
                if (code.charAt(scanned) == '{') depth++;
                else if (code.charAt(scanned) == '}') depth--;
            }
            int comment = source.indexOf('/', sections.start());
            if (depth == 1 && JavaEditing.codeAt(lexicalSource, comment)) {
                result.append(source, kept, sections.start());
                kept = sections.end();
            }
        } while (sections.find());
        return kept == 0 ? source : result.append(source, kept, source.length()).toString();
    }

    static String nativeBody(String betweenBraces) {
        return reindent(betweenBraces, "", true);
    }

    static String editorBody(String nativeBody) {
        return editorBody(nativeBody, "        ");
    }

    static String editorBody(String nativeBody, String indent) {
        return reindent(nativeBody, indent, false);
    }

    static boolean hasBodyPadding(String nativeBody) {
        return !nativeBody.equals(reindent(nativeBody, "", false));
    }

    private static String reindent(String source, String prefix, boolean afterOpeningBrace) {
        String[] lines = source.lines().toArray(String[]::new);
        int first = 0, end = lines.length;
        while (first < end && blank(lines[first])) first++;
        while (end > first && blank(lines[end - 1])) end--;
        if (first == end) return "";

        // The first line may start directly after '{', with a different margin from later lines.
        boolean inline = afterOpeningBrace && first == 0;
        int common = Integer.MAX_VALUE;
        for (int index = inline ? first + 1 : first; index < end; index++) {
            if (!blank(lines[index])) common = Math.min(common, indentation(lines[index]));
        }
        if (inline && end < lines.length) {
            // A continuation can be the only later line. Keep its indentation beyond the method margin.
            common = Math.min(common, indentation(lines[lines.length - 1]) + 4);
        }
        StringJoiner result = new StringJoiner("\n");
        for (int index = first; index < end; index++) {
            String line = lines[index];
            boolean empty = blank(line);
            int remove = inline && index == first ? indentation(line) : common;
            // Shift every nonblank line uniformly, including the contents and delimiter of text blocks.
            String shifted = empty ? "" : prefix + line.substring(remove);
            result.add(index == end - 1 ? shifted.stripTrailing() : shifted);
        }
        return result.toString();
    }

    private static boolean blank(String line) {
        return indentation(line) == line.length();
    }

    private static int indentation(String line) {
        int length = 0;
        while (length < line.length() && (line.charAt(length) == ' ' || line.charAt(length) == '\t')) length++;
        return length;
    }
}
