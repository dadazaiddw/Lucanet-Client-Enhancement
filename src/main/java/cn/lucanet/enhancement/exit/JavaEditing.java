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
    static int lineEnd(String text,int at){int p=text.indexOf('\n',at);return p<0?text.length():p>0&&text.charAt(p-1)=='\r'?p-1:p;}
    private static int afterSeparator(String text,int end){
        if(end<text.length()&&text.charAt(end)=='\r'&&end+1<text.length()&&text.charAt(end+1)=='\n')return end+2;
        return end<text.length()&&text.charAt(end)=='\n'?end+1:end;
    }
    private static String newline(String text){int first=text.indexOf('\n');return first>0&&text.charAt(first-1)=='\r'?"\r\n":"\n";}
    private static String separator(String text,int end){int next=afterSeparator(text,end);return next>end?text.substring(end,next):newline(text);}
    static int[] lines(JTextComponent editor) {
        String text=editor.getText();int start=editor.getSelectionStart(),end=editor.getSelectionEnd();
        if(end>start && end>0 && text.charAt(end-1)=='\n')end--;
        return new int[]{lineStart(text,start),lineEnd(text,end)};
    }
    static void duplicate(JTextComponent editor) {
        String text=editor.getText();int start=editor.getSelectionStart(),end=editor.getSelectionEnd();
        if(start!=end){String selected=text.substring(start,end);replace(editor,end,end,selected);editor.select(end,end+selected.length());}
        else {int[] range=lines(editor);String line=text.substring(range[0],range[1]),breakLine=separator(text,range[1]);replace(editor,range[1],range[1],breakLine+line);editor.setCaretPosition(end+line.length()+breakLine.length());}
    }
    static void deleteLines(JTextComponent editor) {
        String text=editor.getText();int[] range=lines(editor);
        if(range[1]<text.length())range[1]=afterSeparator(text,range[1]);
        else if(range[0]>0){range[0]--;if(range[0]>0&&text.charAt(range[0]-1)=='\r')range[0]--;}
        replace(editor,range[0],range[1],"");
    }
    static void move(JTextComponent editor,int direction) {
        String text=editor.getText();int[] r=lines(editor);int caret=editor.getCaretPosition();boolean selected=editor.getSelectionStart()!=editor.getSelectionEnd();
        int start=r[0],end=r[1],shift;
        if(direction<0) {
            if(start==0)return;int before=lineStart(text,start-1),previousEnd=lineEnd(text,before);String previous=text.substring(before,previousEnd);
            replace(editor,before,end,text.substring(start,end)+text.substring(previousEnd,start)+previous);shift=before-start;
        }else{
            if(end==text.length())return;int nextStart=afterSeparator(text,end),after=lineEnd(text,nextStart);String next=text.substring(nextStart,after);
            replace(editor,start,after,next+text.substring(end,nextStart)+text.substring(start,end));shift=next.length()+nextStart-end;
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
        if(line.stripTrailing().endsWith("{"))indent+="    ";replace(editor,end,end,separator(text,end)+indent);
    }
    static void join(JTextComponent editor) {
        String text=editor.getText();int end=lineEnd(text,editor.getCaretPosition());if(end==text.length())return;
        // Joining a // comment can silently comment out executable code.
        int next=afterSeparator(text,end);
        if(!codeAt(text,end)||!codeAt(text,next))return;
        while(next<text.length()&&(text.charAt(next)==' '||text.charAt(next)=='\t'))next++;
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
        value=value.replace("\n",newline(text)+indent);int cursor=value.indexOf('|');
        replace(editor,start,end,value.replace("|",""));editor.setCaretPosition(start+cursor);return true;
    }
    static boolean codeAt(String text,int at) {
        // A sentinel is left visible only when the caret is outside a literal/comment.
        return mask(text.substring(0,at)+"\u0001").charAt(at)=='\u0001';
    }
    /** Only typed delimiters use this path; paste and programmatic document changes stay literal. */
    static void typePair(JTextComponent editor,char typed){
        String text=editor.getText();int start=editor.getSelectionStart(),end=editor.getSelectionEnd();
        char closer=closing(typed);boolean code=codeAt(text,start);
        if(start!=end&&closer!=0&&code&&(typed=='"'||typed=='\''||codeAt(text,end))){
            String selected=text.substring(start,end);
            if(typed=='"'||typed=='\'')selected=selected.replace("\\","\\\\").replace(String.valueOf(typed),"\\"+typed).replace("\r","\\r").replace("\n","\\n").replace("\t","\\t");
            replace(editor,start,end,typed+selected+closer);editor.select(start+1,start+1+selected.length());return;
        }
        if(start==end){
            char next=end<text.length()?text.charAt(end):0;
            if((typed=='"'||typed=='\'')&&next==typed&&!escaped(text,start)){
                int state=literalState(text,start);
                if(state==(typed=='"'?3:4)){editor.setCaretPosition(start+1);return;}
            }
            if(")]}".indexOf(typed)>=0&&next==typed&&code&&matchingOpen(text,start,typed)){
                editor.setCaretPosition(start+1);return;
            }
            // A third quote starts a text block. Do not turn it into four quotes.
            boolean textBlock=typed=='"'&&start>=2&&text.startsWith("\"\"",start-2)&&codeAt(text,start-2);
            if(closer!=0&&code&&!textBlock&&(next==0||Character.isWhitespace(next)||",;:)]}".indexOf(next)>=0)){
                replace(editor,start,end,""+typed+closer);editor.setCaretPosition(start+1);return;
            }
        }
        replace(editor,start,end,String.valueOf(typed));
    }
    private static char closing(char c){switch(c){case '(':return ')';case '[':return ']';case '{':return '}';case '"':case '\'':return c;default:return 0;}}
    private static boolean escaped(String text,int at){int count=0;while(at>0&&text.charAt(--at)=='\\')count++;return count%2!=0;}
    private static boolean matchingOpen(String text,int at,char closer){
        String code=mask(text.substring(0,at));Deque<Character> stack=new ArrayDeque<>();
        for(int i=0;i<code.length();i++){
            char c=code.charAt(i);
            if("([{".indexOf(c)>=0)stack.push(c);
            else if(")]}".indexOf(c)>=0){if(stack.isEmpty()||closing(stack.pop())!=c)return false;}
        }
        return !stack.isEmpty()&&closing(stack.peek())==closer;
    }
    private static int literalState(String text,int at){
        int state=0;
        for(int i=0;i<at;i++){
            char c=text.charAt(i),next=i+1<at?text.charAt(i+1):0;
            if(state==0){
                if(c=='/'&&(next=='/'||next=='*')){state=next=='/'?1:2;i++;}
                else if(c=='"'&&i+2<at&&text.startsWith("\"\"\"",i)){state=5;i+=2;}
                else if(c=='"'||c=='\'')state=c=='"'?3:4;
            }else if(state==1&&c=='\n')state=0;
            else if(state==2&&c=='*'&&next=='/'){state=0;i++;}
            else if(state>=3&&c=='\\')i++;
            else if(state==5&&i+2<at&&text.startsWith("\"\"\"",i)){state=0;i+=2;}
            else if(state==3&&c=='"'||state==4&&c=='\'')state=0;
        }
        return state;
    }
    static boolean backspacePair(JTextComponent editor){
        String text=editor.getText();int at=editor.getCaretPosition();
        if(editor.getSelectionStart()!=editor.getSelectionEnd()||at==0||at>=text.length())return false;
        char opener=text.charAt(at-1);
        if(closing(opener)==0||closing(opener)!=text.charAt(at)||!codeAt(text,at-1))return false;
        if(opener=='"'&&(at>=2&&text.charAt(at-2)=='"'||at+1<text.length()&&text.charAt(at+1)=='"'))return false;
        replace(editor,at-1,at+1,"");return true;
    }
    static boolean enterPair(JTextComponent editor){
        if(editor.getSelectionStart()!=editor.getSelectionEnd())return false;
        String text=editor.getText();int at=editor.getCaretPosition(),left=at,right=at;
        if(!codeAt(text,at))return false;
        while(left>0&&(text.charAt(left-1)==' '||text.charAt(left-1)=='\t'))left--;
        while(right<text.length()&&(text.charAt(right)==' '||text.charAt(right)=='\t'))right++;
        if(left==0||right==text.length()||text.charAt(left-1)!='{'||text.charAt(right)!='}')return false;
        String line=text.substring(lineStart(text,left-1),left-1),indent=line.substring(0,line.length()-line.stripLeading().length());
        String unit="    ";
        try{int width=((Number)JavaEditorContext.call(editor,"getTabSize")).intValue();if(width>0&&width<=16)unit=" ".repeat(width);}catch(Throwable unavailable){}
        try{if(Boolean.FALSE.equals(JavaEditorContext.call(editor,"getTabsEmulated")))unit="\t";}catch(Throwable unavailable){if(indent.contains("\t"))unit="\t";}
        String inside=newline(text)+indent+unit;
        replace(editor,left,right,inside+newline(text)+indent);editor.setCaretPosition(left+inside.length());return true;
    }

    static final class CallInsertion {
        final String text;final int end,caret;final boolean parameters;
        CallInsertion(String text,int end,int caret,boolean parameters){this.text=text;this.end=end;this.caret=caret;this.parameters=parameters;}
    }
    static CallInsertion callInsertion(String source,int end,String stem,boolean constructor,boolean parametersExpected){
        CallInsertion plan=callInsertion(source,end,stem,constructor);
        if(parametersExpected||!plan.parameters)return plan;
        String completed=mask(plan.text+source.substring(plan.end));int depth=1;
        for(int at=plan.caret;at<completed.length();at++){
            char c=completed.charAt(at);
            if(c=='(')depth++;
            else if(c==')'&&--depth==0)return new CallInsertion(plan.text,plan.end,at+1,false);
        }
        return plan;
    }
    static void finishVariable(JTextComponent editor,int statementEnd){
        String source=editor.getText();int end=lineEnd(source,statementEnd);
        String tail=source.substring(statementEnd,end);
        if(tail.isBlank()||tail.stripLeading().startsWith("//")){
            editor.setCaretPosition(end);newLine(editor);return;
        }
        String line=source.substring(lineStart(source,statementEnd),statementEnd);
        String indent=line.substring(0,line.length()-line.stripLeading().length()),breakLine=newline(source);
        // A following statement/closing brace stays on its own line, after the new blank line.
        replace(editor,statementEnd,statementEnd,breakLine+indent+breakLine+indent);
        editor.setCaretPosition(statementEnd+breakLine.length()+indent.length());
    }
    /** Reuses existing type arguments, trivia and call arguments without replacing the latter. */
    static CallInsertion callInsertion(String source,int end,String stem,boolean constructor){
        int next=triviaEnd(source,end),genericEnd=end;
        if(constructor&&next<source.length()&&source.charAt(next)=='<'){
            String code=mask(source);int depth=0,close=-1;
            for(int i=next;i<code.length();i++){char c=code.charAt(i);if(c=='<')depth++;else if(c=='>'&&--depth==0){close=i+1;break;}else if(c==';'||c=='{'||c=='}')break;}
            if(close<0)return new CallInsertion(stem,end,stem.length(),false);
            genericEnd=close;next=triviaEnd(source,close);
        }
        if(constructor&&next<source.length()&&source.charAt(next)=='[')return new CallInsertion(stem,end,stem.length(),false);
        if(next<source.length()&&source.charAt(next)=='('){
            // Also close a lone '(' at a statement boundary; existing arguments stay untouched.
            int after=triviaEnd(source,next+1);
            if(after==source.length()||";}".indexOf(source.charAt(after))>=0){
                String insertion=stem+source.substring(end,next+1)+")";
                return new CallInsertion(insertion,next+1,insertion.length()-1,true);
            }
            return new CallInsertion(stem,end,stem.length()+next-end+1,true);
        }
        String insertion=stem+source.substring(end,genericEnd)+"()";
        return new CallInsertion(insertion,genericEnd,insertion.length()-1,true);
    }
    private static int triviaEnd(String text,int at){
        while(at<text.length()){
            if(Character.isWhitespace(text.charAt(at))){at++;continue;}
            if(text.startsWith("/*",at)){int end=text.indexOf("*/",at+2);if(end<0)break;at=end+2;continue;}
            if(text.startsWith("//",at)){int end=text.indexOf('\n',at+2);if(end<0)break;at=end+1;continue;}
            break;
        }
        return at;
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
            int next=Math.min(range[1],afterSeparator(text,end));
            if(end<next)result.append(text,end,next);start=next;
        }
        if(!result.toString().equals(text.substring(range[0],range[1])))replace(editor,range[0],range[1],result.toString());
    }
}
