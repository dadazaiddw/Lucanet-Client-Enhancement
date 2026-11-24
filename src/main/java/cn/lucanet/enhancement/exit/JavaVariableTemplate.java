package cn.lucanet.enhancement.exit;

import javax.lang.model.SourceVersion;
import javax.swing.event.DocumentEvent;
import javax.swing.text.Document;
import javax.swing.text.JTextComponent;

/** Tracks only the editable name created by .var, never an arbitrary selection. */
final class JavaVariableTemplate {
    private final Document document;
    private final int start;
    private int end;
    int statementEnd;
    JavaVariableTemplate(Document document,int start,int end,int statementEnd){
        this.document=document;this.start=start;this.end=end;this.statementEnd=statementEnd;
    }
    boolean contains(JTextComponent editor){
        return editor!=null&&editor.getDocument()==document&&editor.getSelectionStart()>=start&&editor.getSelectionEnd()<=end;
    }
    boolean update(DocumentEvent event,boolean insert){
        int at=event.getOffset(),length=event.getLength();
        if(event.getDocument()!=document||at<start||at>end||!insert&&at+length>end)return false;
        int delta=insert?length:-length;end+=delta;statementEnd+=delta;return true;
    }
    boolean valid(JTextComponent editor){
        if(!contains(editor)||statementEnd>document.getLength()||statementEnd<=end)return false;
        String name=editor.getText().substring(start,end);
        return SourceVersion.isIdentifier(name)&&!SourceVersion.isKeyword(name)&&editor.getText().charAt(statementEnd-1)==';';
    }
}
