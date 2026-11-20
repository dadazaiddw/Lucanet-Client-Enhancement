package cn.lucanet.enhancement.exit;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.swing.*;
import javax.swing.text.*;

/** Shows javac errors without changing the source document or its undo history. */
final class JavaSourceDiagnostics {
    static final String DISPLAY=CustomJavaModel.DOCUMENT+".diagnostics";
    private static final Color ERROR=new Color(183,46,49);
    private static final Highlighter.HighlightPainter UNDERLINE=new ErrorUnderline();
    private final JButton button=new JButton();
    private final List<Object> tags=new ArrayList<>();
    private List<Map<String,String>> problems=List.of();
    private Highlighter highlighter;
    private JPopupMenu menu;

    JavaSourceDiagnostics(JTextComponent source){
        button.setName("customJavaDiagnostics");
        button.setHorizontalAlignment(SwingConstants.LEFT);
        button.setForeground(ERROR);
        button.setBorder(BorderFactory.createEmptyBorder(4,12,4,12));
        button.setContentAreaFilled(false);
        button.setVisible(false);
        button.addActionListener(event->showProblems(source));
    }

    JComponent component(){return button;}

    void show(JTextComponent source,List<Map<String,String>> errors){
        clear();
        if(errors.isEmpty())return;
        problems=errors;
        Highlighter painter=source.getHighlighter();
        highlighter=painter;
        String body=source.getText();
        for(Map<String,String> problem:problems){
            int start=Integer.parseInt(problem.get("start"));
            if(start>=body.length()&&start>0)start--;
            int end=Math.min(body.length(),Math.max(start+1,Integer.parseInt(problem.get("end"))));
            int lineEnd=body.indexOf('\n',start);
            if(lineEnd>start)end=Math.min(end,lineEnd);
            if(painter!=null&&start<end)try{tags.add(painter.addHighlight(start,end,UNDERLINE));}
            catch(BadLocationException ignored){}
        }
        String description=describe(problems.get(0));
        button.setText(problems.size()+" 个错误 · "+description);
        button.setToolTipText(description);
        button.setVisible(true);
    }

    void clear(){
        if(menu!=null){menu.setVisible(false);menu=null;}
        if(highlighter!=null)for(Object tag:tags)highlighter.removeHighlight(tag);
        highlighter=null;
        tags.clear();
        if(!problems.isEmpty()){
            problems=List.of();
            button.setText("");
            button.setToolTipText(null);
            button.setVisible(false);
        }
    }

    private void showProblems(JTextComponent source){
        menu=new JPopupMenu();
        for(Map<String,String> problem:problems){
            JMenuItem item=new JMenuItem(describe(problem));
            item.setFont(button.getFont());
            item.addActionListener(event->{
                source.setCaretPosition(Math.min(source.getDocument().getLength(),Integer.parseInt(problem.get("start"))));
                source.requestFocusInWindow();
            });
            menu.add(item);
        }
        menu.show(button,0,-menu.getPreferredSize().height);
    }

    private static String describe(Map<String,String> problem){
        return problem.get("line")+":"+problem.get("column")+"  "+problem.get("message");
    }

    private static final class ErrorUnderline extends LayeredHighlighter.LayerPainter {
        @Override public void paint(Graphics graphics,int start,int end,Shape bounds,JTextComponent text){}
        @Override public Shape paintLayer(Graphics graphics,int start,int end,Shape bounds,JTextComponent text,View view){
            try{
                Rectangle line=view.modelToView(start,Position.Bias.Forward,end,Position.Bias.Backward,bounds).getBounds();
                int y=line.y+line.height-2;
                graphics.setColor(ERROR);
                for(int x=line.x;x<line.x+line.width;x+=4){
                    graphics.drawLine(x,y,x+2,y-2);
                    graphics.drawLine(x+2,y-2,x+4,y);
                }
                return line;
            }catch(BadLocationException stale){return null;}
        }
    }
}
