package cn.lucanet.enhancement.exit;

import javax.swing.text.*;
import java.util.*;

/** Local edits on the original document, grouped into the native RSyntax undo history. */
final class JavaEditing {
    private JavaEditing(){}
    static void atomic(JTextComponent text,Runnable edit) {
        boolean grouped=false;
        try{JavaEditorContext.call(text,"beginAtomicEdit");grouped=true;}catch(Throwable ignored){}
        try{edit.run();}finally{if(grouped)try{JavaEditorContext.call(text,"endAtomicEdit");}catch(Throwable ignored){}}
    }
    static void replace(JTextComponent text,int start,int end,String value) {
        text.select(start,end);text.replaceSelection(value);
    }
    static int lineStart(String text,int at){return text.lastIndexOf('\n',Math.max(-1,at-1))+1;}
    static int lineEnd(String text,int at){int p=text.indexOf('\n',at);return p<0?text.length():p;}
    static int[] lines(JTextComponent editor) {
        String text=editor.getText();int start=editor.getSelectionStart(),end=editor.getSelectionEnd();
        if(end>start && end>0 && text.charAt(end-1)=='\n')end--;
        return new int[]{lineStart(text,start),lineEnd(text,end)};
    }
    static void duplicate(JTextComponent editor) {
        String text=editor.getText();int start=editor.getSelectionStart(),end=editor.getSelectionEnd();
        if(start!=end){String selected=text.substring(start,end);replace(editor,end,end,selected);editor.select(end,end+selected.length());}
        else {int[] range=lines(editor);String line=text.substring(range[0],range[1]);replace(editor,range[1],range[1],"\n"+line);editor.setCaretPosition(end+line.length()+1);}
    }
    static void deleteLines(JTextComponent editor) {
        String text=editor.getText();int[] range=lines(editor);
        if(range[1]<text.length())range[1]++;else if(range[0]>0)range[0]--;
        replace(editor,range[0],range[1],"");
    }
    static void move(JTextComponent editor,int direction) {
        String text=editor.getText();int[] r=lines(editor);int caret=editor.getCaretPosition();boolean selected=editor.getSelectionStart()!=editor.getSelectionEnd();
        int start=r[0],end=r[1],shift;
        if(direction<0) {
            if(start==0)return;int before=lineStart(text,start-1);String previous=text.substring(before,start-1);
            replace(editor,before,end,text.substring(start,end)+"\n"+previous);shift=before-start;
        }else{
            if(end==text.length())return;int after=lineEnd(text,end+1);String next=text.substring(end+1,after);
            replace(editor,start,after,next+"\n"+text.substring(start,end));shift=next.length()+1;
        }
        if(selected)editor.select(start+shift,end+shift);else editor.setCaretPosition(caret+shift);
    }
    static void comment(JTextComponent editor) {
        String text=editor.getText();int[] r=lines(editor);String[] lines=text.substring(r[0],r[1]).split("\n",-1);
        boolean remove=Arrays.stream(lines).filter(s->!s.trim().isEmpty()).allMatch(s->s.stripLeading().startsWith("//"));
        StringJoiner result=new StringJoiner("\n");
        for(String line:lines) {
            int indent=line.length()-line.stripLeading().length();
            if(!line.trim().isEmpty())line=remove?line.substring(0,indent)+line.substring(indent+2):line.substring(0,indent)+"//"+line.substring(indent);
            result.add(line);
        }
        replace(editor,r[0],r[1],result.toString());editor.select(r[0],r[0]+result.length());
    }
    static void blockComment(JTextComponent editor) {
        String text=editor.getText();int start=editor.getSelectionStart(),end=editor.getSelectionEnd();
        String selected=text.substring(start,end);
        if(selected.startsWith("/*")&&selected.endsWith("*/"))replace(editor,start,end,selected.substring(2,selected.length()-2));
        else if(!selected.contains("*/")){replace(editor,start,end,"/*"+selected+"*/");editor.select(start+2,start+2+selected.length());}
    }
    static void newLine(JTextComponent editor) {
        String text=editor.getText();int end=lineEnd(text,editor.getCaretPosition());int start=lineStart(text,end);
        String line=text.substring(start,end);String indent=line.substring(0,line.length()-line.stripLeading().length());
        if(line.stripTrailing().endsWith("{"))indent+="    ";replace(editor,end,end,"\n"+indent);
    }
    static void join(JTextComponent editor) {
        String text=editor.getText();int end=lineEnd(text,editor.getCaretPosition());if(end==text.length())return;
        // Joining a // comment can silently comment out executable code.
        if(!codeAt(text,end)||!codeAt(text,end+1))return;
        int next=end+1;while(next<text.length()&&(text.charAt(next)==' '||text.charAt(next)=='\t'))next++;
        replace(editor,end,next," ");
    }
    static boolean template(JTextComponent editor) {
        String text=editor.getText();int end=editor.getCaretPosition(),start=end;
        while(start>0 && Character.isJavaIdentifierPart(text.charAt(start-1)))start--;
        String key=text.substring(start,end),value;
        switch(key) {
            case "sout":value="System.out.println(|);";break;
            case "serr":value="System.err.println(|);";break;
            case "fori":value="for (int i = 0; i < |; i++) {\n    \n}";break;
            case "iter":value="for (Object item : |) {\n    \n}";break;
            case "ifn":value="if (| == null) {\n    \n}";break;
            case "inn":value="if (| != null) {\n    \n}";break;
            default:return false;
        }
        if(!codeAt(text,start))return false;
        String line=text.substring(lineStart(text,start),start);String indent=line.substring(0,line.length()-line.stripLeading().length());
        value=value.replace("\n","\n"+indent);int cursor=value.indexOf('|');
        replace(editor,start,end,value.replace("|",""));editor.setCaretPosition(start+cursor);return true;
    }
    static boolean codeAt(String text,int at) {
        // A sentinel is left visible only when the caret is outside a literal/comment.
        return mask(text.substring(0,at)+"\u0001").charAt(at)=='\u0001';
    }
    /** Same-length lexical mask for comments, strings, characters and Java text blocks. */
    static String mask(String text) {
        StringBuilder out=new StringBuilder(text);int state=0;
        for(int i=0;i<text.length();i++) {
            char c=text.charAt(i),n=i+1<text.length()?text.charAt(i+1):0;
            if(state==0) {
                if(c=='/'&&(n=='/'||n=='*')) {state=n=='/'?1:2;out.setCharAt(i,' ');out.setCharAt(++i,' ');}
                else if(c=='"'&&text.startsWith("\"\"\"",i)){state=5;for(int j=0;j<3;j++)out.setCharAt(i+j,' ');i+=2;}
                else if(c=='"'||c=='\''){state=c=='"'?3:4;out.setCharAt(i,' ');}
            }else {
                if(c!='\n'&&c!='\r')out.setCharAt(i,' ');
                if(state==1&&c=='\n')state=0;
                else if(state==2&&c=='*'&&n=='/'){out.setCharAt(++i,' ');state=0;}
                else if((state==3||state==4||state==5)&&c=='\\'&&i+1<text.length()){out.setCharAt(++i,' ');}
                else if(state==5&&text.startsWith("\"\"\"",i)){for(int j=0;j<3;j++)out.setCharAt(i+j,' ');i+=2;state=0;}
                else if(state==3&&c=='"'||state==4&&c=='\'')state=0;
            }
        }
        return out.toString();
    }
    /** Indentation only: never rewrites tokens, literals, text blocks or line endings. */
    static void indent(JTextComponent editor) {
        String text=editor.getText(),masked=mask(text);int[] range=editor.getSelectionStart()==editor.getSelectionEnd()?new int[]{0,text.length()}:lines(editor);
        int depth=0;
        for(int i=0;i<range[0];i++){if(masked.charAt(i)=='{')depth++;if(masked.charAt(i)=='}')depth=Math.max(0,depth-1);}
        StringBuilder result=new StringBuilder();int start=range[0];
        while(start<range[1]) {
            int end=Math.min(range[1],lineEnd(text,start));String line=text.substring(start,end),tokens=masked.substring(start,end).stripLeading();
            int leading=line.length()-line.stripLeading().length();
            boolean literal=!codeAt(text,start+leading) && !line.stripLeading().startsWith("//") && !line.stripLeading().startsWith("/*");
            if(literal)result.append(line);else result.append("    ".repeat(Math.max(0,depth-(tokens.startsWith("}")?1:0)))).append(line.stripLeading());
            for(char c:tokens.toCharArray()){if(c=='{')depth++;if(c=='}')depth=Math.max(0,depth-1);}
            if(end<range[1])result.append('\n');start=end+1;
        }
        if(!result.toString().equals(text.substring(range[0],range[1])))replace(editor,range[0],range[1],result.toString());
    }
}
