package cn.lucanet.enhancement.exit;

import java.awt.*;
import java.awt.event.*;
import java.util.*;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.*;
import javax.swing.border.AbstractBorder;
import javax.swing.event.*;

/** Local speed search: keys are consumed only while this editor's popup is open. */
final class JavaGenerationMenu extends JPopupMenu implements KeyEventDispatcher {
    private static final Color TEXT=new Color(31,33,38),SELECTED=new Color(212,226,255),LINE=new Color(224,226,232);
    private final List<Command> commands=new ArrayList<>();
    private final JLabel search=new JLabel(),empty=new JLabel("No matching commands",SwingConstants.CENTER);
    private final JSeparator separator=new JSeparator();
    private String query="";
    private boolean listening;

    JavaGenerationMenu(int size,Consumer<String> execute){
        setName("customJavaGenerationMenu");setOpaque(false);
        setBorder(BorderFactory.createCompoundBorder(new AbstractBorder(){
            @Override public Insets getBorderInsets(Component c){return new Insets(1,1,1,1);}
            @Override public void paintBorder(Component c,Graphics g,int x,int y,int w,int h){Graphics2D copy=(Graphics2D)g.create();copy.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);copy.setColor(new Color(181,185,197));copy.drawRoundRect(x,y,w-1,h-1,14,14);copy.dispose();}
        },BorderFactory.createEmptyBorder(6,10,8,10)));
        JLabel title=new JLabel("Generate",SwingConstants.CENTER){@Override public Dimension getMaximumSize(){return new Dimension(Integer.MAX_VALUE,getPreferredSize().height);}};title.setAlignmentX(0.5f);title.setName("customJavaGenerationTitle");title.setBorder(BorderFactory.createEmptyBorder(6,6,12,6));title.setFont(new Font(Font.DIALOG,Font.BOLD,size));add(title);
        search.setName("customJavaGenerationSearch");search.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0,0,1,0,LINE),BorderFactory.createEmptyBorder(4,8,9,8)));search.setVisible(false);add(search);
        String[][] entries={{"constructor","Constructor","构造方法"},{"getter","Getter","读取"},{"setter","Setter","设置"},{"accessors","Getter and Setter","访问器"},{"equalsHashCode","equals() and hashCode()","相等 哈希"},{"toString","toString()","字符串"},{"override","Override Methods...","重写 实现方法"}};
        for(String[] entry:entries){
            if(entry[0].equals("override")){separator.setForeground(LINE);separator.setPreferredSize(new Dimension(1,15));add(separator);}
            Command item=new Command(entry[0],entry[1],entry[2]);commands.add(item);add(item);
            item.addActionListener(event->{MenuSelectionManager.defaultManager().clearSelectedPath();setVisible(false);EventQueue.invokeLater(()->execute.accept(item.id));});
        }
        empty.setForeground(new Color(119,124,136));empty.setBorder(BorderFactory.createEmptyBorder(12,8,12,8));empty.setVisible(false);add(empty);
        JavaGenerationDialog.font(this,size);
        addPopupMenuListener(new PopupMenuListener(){
            public void popupMenuWillBecomeVisible(PopupMenuEvent event){if(!listening){KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(JavaGenerationMenu.this);listening=true;}EventQueue.invokeLater(()->{if(isVisible())select(0);});}
            public void popupMenuWillBecomeInvisible(PopupMenuEvent event){detach();}
            public void popupMenuCanceled(PopupMenuEvent event){detach();}
        });
    }
    private void detach(){if(listening){KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(this);listening=false;}}
    @Override protected void paintComponent(Graphics g){Graphics2D copy=(Graphics2D)g.create();copy.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);copy.setColor(Color.WHITE);copy.fillRoundRect(0,0,getWidth(),getHeight(),14,14);copy.dispose();}
    private List<Command> visible(){List<Command> list=new ArrayList<>();for(Command item:commands)if(item.isVisible())list.add(item);return list;}
    private void select(int index){List<Command> values=visible();if(values.isEmpty()){MenuSelectionManager.defaultManager().setSelectedPath(new MenuElement[]{this});return;}Command item=values.get(Math.floorMod(index,values.size()));MenuSelectionManager.defaultManager().setSelectedPath(new MenuElement[]{this,item});}
    private int selected(){List<Command> values=visible();for(int i=0;i<values.size();i++)if(values.get(i).isArmed())return i;return 0;}
    private void filter(String value){
        query=value;String match=query.toLowerCase(Locale.ROOT);
        search.setText("Search: "+query);search.setVisible(!query.isEmpty());
        for(Command item:commands)item.setVisible((item.getText()+" "+item.alias).toLowerCase(Locale.ROOT).contains(match));
        List<Command> values=visible();empty.setVisible(values.isEmpty());separator.setVisible(values.size()>1&&values.get(values.size()-1).id.equals("override"));
        select(0);pack();revalidate();repaint();
    }
    @Override public boolean dispatchKeyEvent(KeyEvent event){
        if(!isVisible())return false;
        if(event.getID()==KeyEvent.KEY_TYPED&&!event.isControlDown()&&!event.isAltDown()&&!event.isMetaDown()){
            char c=event.getKeyChar();if(!Character.isISOControl(c)&&c!=KeyEvent.CHAR_UNDEFINED){filter(query+c);event.consume();return true;}
        }
        if(event.getID()!=KeyEvent.KEY_PRESSED)return false;
        if(event.isControlDown()&&event.getKeyCode()==KeyEvent.VK_O){commands.get(commands.size()-1).doClick();event.consume();return true;}
        switch(event.getKeyCode()){
            case KeyEvent.VK_ESCAPE:if(query.isEmpty()){MenuSelectionManager.defaultManager().clearSelectedPath();setVisible(false);}else filter("");break;
            case KeyEvent.VK_BACK_SPACE:if(!query.isEmpty())filter(event.isControlDown()?"":query.substring(0,query.offsetByCodePoints(query.length(),-1)));break;
            case KeyEvent.VK_UP:select(selected()-1);break;
            case KeyEvent.VK_DOWN:select(selected()+1);break;
            case KeyEvent.VK_HOME:select(0);break;
            case KeyEvent.VK_END:select(visible().size()-1);break;
            case KeyEvent.VK_ENTER:List<Command> values=visible();if(!values.isEmpty())values.get(selected()).doClick();break;
            default:
                // Prevent Delete, shortcuts and pressed-key editor bindings from editing the source.
                // The subsequent printable KEY_TYPED event still performs the search.
                if(event.getKeyCode()==KeyEvent.VK_ALT||event.getKeyCode()==KeyEvent.VK_CONTROL||event.getKeyCode()==KeyEvent.VK_SHIFT||event.getKeyCode()==KeyEvent.VK_META)return false;
                break;
        }
        event.consume();return true;
    }
    private final class Command extends JMenuItem {
        final String id,alias;
        Command(String id,String label,String alias){super(label);this.id=id;this.alias=alias;setName("customJavaGenerate-"+id);setOpaque(false);setBorder(BorderFactory.createEmptyBorder(7,32,7,14));}
        @Override public Dimension getPreferredSize(){FontMetrics metrics=getFontMetrics(getFont());return new Dimension(Math.max(340,Math.max(metrics.stringWidth("equals() and hashCode()")+80,metrics.stringWidth("Override Methods...")+metrics.stringWidth("Ctrl+O")+78)),metrics.getHeight()+14);}
        @Override protected void paintComponent(Graphics g){
            Graphics2D copy=(Graphics2D)g.create();copy.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);copy.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            if(isArmed()){copy.setColor(SELECTED);copy.fillRoundRect(0,0,getWidth(),getHeight(),10,10);}
            copy.setFont(getFont());FontMetrics metrics=copy.getFontMetrics();int x=32,y=(getHeight()-metrics.getHeight())/2+metrics.getAscent();
            String label=getText();int from=label.toLowerCase(Locale.ROOT).indexOf(query.toLowerCase(Locale.ROOT));
            if(!query.isEmpty()&&from>=0){int left=x+metrics.stringWidth(label.substring(0,from));copy.setColor(new Color(255,229,151));copy.fillRoundRect(left,y-metrics.getAscent(),metrics.stringWidth(label.substring(from,from+query.length())),metrics.getHeight(),3,3);}
            copy.setColor(TEXT);copy.drawString(label,x,y);
            if(id.equals("override")){String shortcut="Ctrl+O";copy.setColor(new Color(130,136,150));copy.drawString(shortcut,getWidth()-14-metrics.stringWidth(shortcut),y);}
            copy.dispose();
        }
    }
}
