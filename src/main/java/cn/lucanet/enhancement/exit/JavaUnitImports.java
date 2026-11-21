package cn.lucanet.enhancement.exit;

import java.util.*;
import java.util.regex.*;
import javax.swing.text.JTextComponent;

/** Imports in full compilation units belong in the document and share its native undo history. */
final class JavaUnitImports {
    private static final Pattern IMPORT=Pattern.compile("\\bimport\\s+(static\\s+)?([\\p{javaJavaIdentifierPart}.$*]+)\\s*;");
    private static final Pattern PACKAGE=Pattern.compile("\\bpackage\\s+([\\p{javaJavaIdentifierPart}.$]+)\\s*;");
    static String packageName(String source){Matcher match=PACKAGE.matcher(JavaEditing.mask(source));return match.find()?match.group(1):"";}
    static List<String> names(String source){List<String> result=new ArrayList<>();Matcher match=IMPORT.matcher(JavaEditing.mask(source));while(match.find())result.add((match.group(1)==null?"":"static ")+match.group(2));return result;}
    static String reference(String source,String qualified,ClassLoader loader){
        String simple=qualified.substring(qualified.lastIndexOf('.')+1),pkg=qualified.substring(0,qualified.lastIndexOf('.'));
        List<String> imports=names(source);
        for(String name:imports)if(!name.startsWith("static ")&&name.endsWith("."+simple)&&!name.equals(qualified))return qualified;
        if(Pattern.compile("\\b(?:class|interface|enum|record)\\s+"+Pattern.quote(simple)+"\\b").matcher(JavaEditing.mask(source)).find()&&!pkg.equals(packageName(source)))return qualified;
        if(pkg.equals("java.lang"))for(String entry:imports)if(entry.endsWith(".*")&&!entry.startsWith("static ")&&!entry.equals("java.lang.*"))try{Class.forName(entry.substring(0,entry.length()-1)+simple,false,loader);return qualified;}catch(ClassNotFoundException ignored){}catch(LinkageError ambiguous){return qualified;}
        return simple;
    }
    static void add(JTextComponent text,String qualified){
        String source=text.getText(),pkg=qualified.substring(0,qualified.lastIndexOf('.')),newline=source.contains("\r\n")?"\r\n":"\n";
        if(pkg.equals("java.lang")||pkg.equals(packageName(source))||names(source).contains(qualified))return;
        // An explicit import also resolves competing wildcards without guessing their contents.
        Matcher imports=IMPORT.matcher(JavaEditing.mask(source));int at=-1;while(imports.find())at=imports.end();
        String insertion;
        if(at>=0)insertion=newline+"import "+qualified+";";
        else {Matcher packageLine=PACKAGE.matcher(JavaEditing.mask(source));at=packageLine.find()?packageLine.end():0;insertion=(at==0?"":newline+newline)+"import "+qualified+";"+(at==0?newline+newline:"");}
        replaceKeepingCaret(text,at,at,insertion);
    }
    static void generated(JTextComponent text,List<Map<String,String>> rows){
        if(rows.isEmpty())return;
        int at=Integer.parseInt(rows.get(0).get("replaceStart"));StringBuilder code=new StringBuilder();Set<String> imports=new TreeSet<>();
        for(Map<String,String> row:rows){
            if(Integer.parseInt(row.get("replaceStart"))!=at||Integer.parseInt(row.get("replaceEnd"))!=at)throw new IllegalArgumentException("Generated methods have different insertion positions");
            String insertion=row.get("insert");code.append(code.length()>0&&"true".equals(row.get("enumSeparator"))?insertion.substring(1):insertion);
            for(String name:row.getOrDefault("imports","").split("\n"))if(!name.isBlank())imports.add(name);
        }
        JavaEditing.replace(text,at,at,code.toString());text.setCaretPosition(at+code.length());
        for(String name:imports)add(text,name);
    }
    static void apply(JTextComponent text,List<String> expected,List<String> requested){
        String source=text.getText();if(!names(source).equals(expected))return;
        List<int[]> ranges=new ArrayList<>();Matcher match=IMPORT.matcher(JavaEditing.mask(source));while(match.find())ranges.add(new int[]{match.start(),match.end()});
        JavaEditing.atomic(text,()->{
            for(int i=ranges.size()-1;i>=0;i--){int[] range=ranges.get(i);String replacement=i<requested.size()?"import "+requested.get(i)+";":"";
                if(i==ranges.size()-1&&requested.size()>ranges.size()){StringBuilder expanded=new StringBuilder(replacement);for(int n=ranges.size();n<requested.size();n++)expanded.append("\nimport ").append(requested.get(n)).append(';');replacement=expanded.toString();}
                if(!source.substring(range[0],range[1]).equals(replacement))replaceKeepingCaret(text,range[0],range[1],replacement);
            }
        });
    }
    private static void replaceKeepingCaret(JTextComponent text,int start,int end,String replacement){
        int dot=text.getCaret().getDot(),mark=text.getCaret().getMark(),delta=replacement.length()-(end-start);
        JavaEditing.replace(text,start,end,replacement);
        dot=dot>=end?dot+delta:dot>start?start+replacement.length():dot;mark=mark>=end?mark+delta:mark>start?start+replacement.length():mark;
        text.setCaretPosition(Math.max(0,Math.min(mark,text.getDocument().getLength())));text.moveCaretPosition(Math.max(0,Math.min(dot,text.getDocument().getLength())));
    }
}
