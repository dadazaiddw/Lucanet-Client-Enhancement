package cn.lucanet.enhancement.exit;

import java.awt.*;
import java.awt.event.*;
import java.util.*;
import javax.swing.*;
import javax.swing.border.*;

/** Lightweight IDEA-style lookup, keeping keyboard focus in the original text component. */
final class JavaCompletionPopup extends JPopupMenu {
    private static final Color BACKGROUND=new Color(250,250,252),SELECTED=new Color(224,232,248),TEXT=new Color(35,38,46),MUTED=new Color(112,118,133);
    final JList<Map<String,String>> list;
    private final JScrollPane scroll;
    private final JLabel footer=new JLabel();
    private int fontSize=18,cellWidth=560;
    private String prefix="";
    private boolean parameters;
    private final Map<Map<String,String>,Row> rows=new IdentityHashMap<>();
    private final Map<Map<String,String>,Expansion> expansions=new IdentityHashMap<>();
    private long renderRevision;
    private boolean expansionQueued;
    private int expansionGeneration;
    private JWindow expansionWindow;
    private JComponent expandedView;
    private Object keepOpenToken;
    private boolean keepOpenInitialized;
    private String fontFamily;
    JavaCompletionPopup(JList<Map<String,String>> list) {
        this.list=list;setFocusable(false);setOpaque(false);setLayout(new BorderLayout());
        setBorder(new AbstractBorder(){
            @Override public Insets getBorderInsets(Component c){return new Insets(7,7,7,7);}
            @Override public void paintBorder(Component c,Graphics graphics,int x,int y,int w,int h){Graphics2D g=(Graphics2D)graphics.create();g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);g.setColor(new Color(179,185,198));g.drawRoundRect(x,y,w-1,h-1,14,14);g.dispose();}
        });
        list.setFocusable(false);list.setBackground(BACKGROUND);list.setForeground(TEXT);list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer((owner,value,index,selected,focus)->rows.computeIfAbsent(value,key->new Row()).getListCellRendererComponent(owner,value,index,selected,focus));
        scroll=new JScrollPane(list,ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(BorderFactory.createEmptyBorder());scroll.getViewport().setBackground(BACKGROUND);scroll.getVerticalScrollBar().setUnitIncrement(32);
        scroll.getHorizontalScrollBar().setFocusable(false);scroll.getVerticalScrollBar().setFocusable(false);
        list.addListSelectionListener(e->{if(!e.getValueIsAdjusting())scheduleExpansion();});
        scroll.getViewport().addChangeListener(e->scheduleExpansion());
        footer.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(1,0,0,0,new Color(224,227,234)),BorderFactory.createEmptyBorder(8,10,3,10)));
        footer.setForeground(MUTED);add(scroll,BorderLayout.CENTER);add(footer,BorderLayout.SOUTH);fontSize(FeatureSettings.DEFAULT_JAVA_FONT_SIZE);
    }
    @Override public void setVisible(boolean visible){if(!visible)hideExpansion();super.setVisible(visible);if(visible)scheduleExpansion();}
    @Override public void removeNotify(){hideExpansion();super.removeNotify();}
    private void hideExpansion(){expansionGeneration++;expansionQueued=false;concealExpansion();if(expansionWindow!=null){expansionWindow.dispose();expansionWindow=null;}}
    private void concealExpansion(){if(expansionWindow!=null&&expansionWindow.isVisible())expansionWindow.setVisible(false);expandedView=null;}
    private void scheduleExpansion(){
        if(!isVisible()||expansionQueued)return;
        // Selection and ensureIndexIsVisible can fire in the same key event. Coalesce only that
        // event, without a timed pause and without hiding the row between consecutive choices.
        expansionQueued=true;int generation=expansionGeneration;
        EventQueue.invokeLater(()->{if(generation!=expansionGeneration)return;expansionQueued=false;showExpansion();});
    }
    private void invalidateRows(){
        renderRevision++;
        // CellRendererPane retains every renderer it paints. Release the previous result set,
        // otherwise per-candidate caching would accumulate old HTML views while typing.
        Set<Container> panes=new HashSet<>();for(Row row:rows.values())if(row.getParent() instanceof CellRendererPane)panes.add(row.getParent());
        for(Container pane:panes)pane.removeAll();rows.clear();expansions.clear();
    }
    private Rectangle screenBounds() {
        GraphicsConfiguration config=getInvoker()==null?null:getInvoker().getGraphicsConfiguration();
        if(config==null)return new Rectangle(0,0,1000,700);
        Rectangle bounds=new Rectangle(config.getBounds());Insets insets=Toolkit.getDefaultToolkit().getScreenInsets(config);
        bounds.x+=insets.left;bounds.y+=insets.top;bounds.width-=insets.left+insets.right;bounds.height-=insets.top+insets.bottom;return bounds;
    }
    /** Separate row preview keeps the lookup and all unselected rows stationary, like IDEA. */
    private JComponent createExpansion(int maximumWidth,int maximumHeight) {
        Map<String,String> value=list.getSelectedValue();if(value==null)return null;
        Rectangle bounds=list.getCellBounds(list.getSelectedIndex(),list.getSelectedIndex());int visibleWidth=bounds==null?cellWidth:bounds.width;
        Expansion cached=expansions.get(value);
        if(cached!=null&&cached.matches(maximumWidth,maximumHeight,visibleWidth))return cached.view;
        Row row=new Row(true);row.getListCellRendererComponent(list,value,list.getSelectedIndex(),true,false);
        if(!row.call||row.fullWidth()<=visibleWidth){expansions.put(value,new Expansion(maximumWidth,maximumHeight,visibleWidth,null));return null;}
        int width=Math.min(maximumWidth,Math.max(visibleWidth+2,row.fullWidth()+2))-2;
        row.layoutWidth=Math.max(100,width);row.setSize(row.getPreferredSize());row.doLayout();
        row.setOpaque(true);row.setBackground(SELECTED);
        JPanel card=new JPanel(new BorderLayout());card.setFocusable(false);card.setBackground(SELECTED);
        card.setBorder(BorderFactory.createLineBorder(new Color(179,185,198)));
        if(row.getHeight()+2>maximumHeight) {
            JScrollPane overflow=new JScrollPane(row,ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
            overflow.setBorder(BorderFactory.createEmptyBorder());overflow.setFocusable(false);overflow.getVerticalScrollBar().setFocusable(false);
            row.layoutWidth-=overflow.getVerticalScrollBar().getPreferredSize().width;card.add(overflow);
        }else card.add(row);
        card.setPreferredSize(new Dimension(width+2,Math.min(maximumHeight,row.getPreferredSize().height+2)));
        // The expanded row is a presentation surface. Double-click still accepts the same item;
        // wheel movement changes the lookup selection area without moving keyboard focus.
        MouseAdapter mouse=new MouseAdapter(){@Override public void mouseClicked(MouseEvent e){
            if(e.getButton()==MouseEvent.BUTTON1&&e.getClickCount()==2){Rectangle bounds=list.getCellBounds(list.getSelectedIndex(),list.getSelectedIndex());if(bounds!=null)list.dispatchEvent(new MouseEvent(list,MouseEvent.MOUSE_CLICKED,e.getWhen(),e.getModifiersEx(),bounds.x+1,bounds.y+1,2,false,MouseEvent.BUTTON1));}
        }};
        for(Component c:new Component[]{card,row,row.symbol,row.main,row.detail})c.addMouseListener(mouse);
        // Swing's menu mouse grabber uses an identity token installed by ComboBoxUI, rather
        // than Boolean.TRUE. Copy it through the public client-property API, without reflection.
        if(!keepOpenInitialized){keepOpenToken=new JComboBox<>().getClientProperty("doNotCancelPopup");keepOpenInitialized=true;}
        keepLookupOpen(card,keepOpenToken);
        if(card.getComponent(0)==row)card.addMouseWheelListener(e->{JScrollBar bar=scroll.getVerticalScrollBar();bar.setValue(bar.getValue()+e.getWheelRotation()*bar.getUnitIncrement()*e.getScrollAmount());});
        expansions.put(value,new Expansion(maximumWidth,maximumHeight,visibleWidth,card));return card;
    }
    private static final class Expansion {
        final int width,height,visibleWidth;final JComponent view;
        Expansion(int width,int height,int visibleWidth,JComponent view){this.width=width;this.height=height;this.visibleWidth=visibleWidth;this.view=view;}
        boolean matches(int width,int height,int visibleWidth){return this.width==width&&this.height==height&&this.visibleWidth==visibleWidth;}
    }
    private static void keepLookupOpen(JComponent component,Object token) {
        component.putClientProperty("doNotCancelPopup",token);
        for(Component child:component.getComponents())if(child instanceof JComponent)keepLookupOpen((JComponent)child,token);
    }
    private void showExpansion() {
        if(!isVisible()||!list.isShowing()){concealExpansion();return;}
        int index=list.getSelectedIndex();Rectangle cell=index<0?null:list.getCellBounds(index,index);
        if(cell==null||!cell.intersects(list.getVisibleRect())){concealExpansion();return;}
        Rectangle screen=screenBounds();expandedView=createExpansion(Math.max(120,screen.width-16),Math.max(80,screen.height-16));
        if(expandedView==null){concealExpansion();return;}
        Point point=new Point(cell.x,cell.y);SwingUtilities.convertPointToScreen(point,list);
        // Do not relocate previews from hidden/offscreen windows onto a user's monitor.
        if(!screen.intersects(new Rectangle(point.x,point.y,cell.width,cell.height)))return;
        displayExpansion(expandedView,screen,point);
    }
    private void displayExpansion(JComponent view,Rectangle screen,Point point) {
        Window owner=SwingUtilities.getWindowAncestor(this);
        if(expansionWindow==null||expansionWindow.getOwner()!=owner){
            if(expansionWindow!=null)expansionWindow.dispose();
            expansionWindow=new JWindow(owner,getGraphicsConfiguration());expansionWindow.setType(Window.Type.POPUP);
            expansionWindow.setFocusableWindowState(false);expansionWindow.setAutoRequestFocus(false);
        }
        boolean changed=expansionWindow.getContentPane()!=view;
        if(changed)expansionWindow.setContentPane(view);
        Dimension size=view.getPreferredSize();
        int x=Math.max(screen.x+8,Math.min(point.x,screen.x+screen.width-size.width-8));
        int y=Math.max(screen.y+8,Math.min(point.y,screen.y+screen.height-size.height-8));
        Rectangle bounds=new Rectangle(x,y,size.width,size.height);
        if(!bounds.equals(expansionWindow.getBounds()))expansionWindow.setBounds(bounds);
        if(changed)expansionWindow.validate();
        if(!expansionWindow.isVisible())expansionWindow.setVisible(true);
        if(changed)view.repaint();
    }
    @Override protected void paintComponent(Graphics graphics){Graphics2D g=(Graphics2D)graphics.create();g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);g.setColor(BACKGROUND);g.fillRoundRect(0,0,getWidth(),getHeight(),14,14);g.dispose();}
    void content(String prefix,boolean parameters) {
        this.prefix=prefix;this.parameters=parameters;
        invalidateRows();
        footer.setText(parameters?"参数信息 · 高亮当前参数":"Java · 当前脚本与依赖库 · "+list.getModel().getSize()+" 个候选");
        list.revalidate();list.repaint();resizeLookup();scroll.getViewport().setViewPosition(new Point(0,0));scheduleExpansion();
    }
    void fontSize(int size) {
        if(fontFamily!=null&&fontSize==size)return;
        fontSize=size;
        if(fontFamily==null){fontFamily="Monospaced";for(String name:GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames())if(name.equals("JetBrains Mono")){fontFamily=name;break;}else if(name.equals("Consolas"))fontFamily=name;}
        invalidateRows();list.setFont(new Font(fontFamily,Font.PLAIN,size));list.setFixedCellHeight(-1);
        footer.setFont(new Font(Font.DIALOG,Font.PLAIN,Math.max(12,size-4)));resizeLookup();scheduleExpansion();
    }
    private void resizeLookup() {
        int rows=Math.max(1,Math.min(parameters?7:10,list.getModel().getSize()));
        Rectangle screen=screenBounds();int screenWidth=screen.width,screenHeight=screen.height;
        int natural=0;
        for(int i=0;i<list.getModel().getSize();i++) {
            Row row=(Row)list.getCellRenderer().getListCellRendererComponent(list,list.getModel().getElementAt(i),i,false,false);
            natural=Math.max(natural,row.naturalWidth());
        }
        int vertical=scroll.getVerticalScrollBar().getPreferredSize().width;
        int width=Math.max(200,Math.min(screenWidth-40,Math.min(1100,Math.max(580,natural+vertical+14))));
        // Constrain the actual list, not just the popup. Otherwise a long HTML signature expands
        // the list's preferred width and puts every right-aligned type outside the viewport.
        cellWidth=width-vertical-14;list.setFixedCellWidth(cellWidth);
        int height=0;
        for(int i=0;i<rows&&i<list.getModel().getSize();i++)height+=list.getCellRenderer().getListCellRendererComponent(list,list.getModel().getElementAt(i),i,false,false).getPreferredSize().height;
        height=Math.min(screenHeight-60,Math.max(fontSize+16,height)+footer.getPreferredSize().height+14);
        setPopupSize(width,height);revalidate();repaint();
    }
    private final class Row extends JPanel implements ListCellRenderer<Map<String,String>> {
        private final JLabel symbol=new JLabel(),main=new LookupLabel(),detail=new LookupLabel();
        private boolean selected,inlineDetail,call;
        private String name="",context="",generics="";
        private String[] args=new String[0];
        private int active=-1;
        private final boolean expanded;
        private int layoutWidth;
        private Map<String,String> boundValue;
        private long revision=-1;
        private int arrangedWidth=-1,arrangedHeight;
        Row(){this(false);}
        Row(boolean expanded){super(null);this.expanded=expanded;setOpaque(false);setFocusable(false);symbol.setName("lookupIcon");main.setName("lookupMain");detail.setName("lookupDetail");add(symbol);add(main);add(detail);}
        @Override public Component getListCellRendererComponent(JList<? extends Map<String,String>> owner,Map<String,String> value,int index,boolean selected,boolean focus) {
            this.selected=selected;if(boundValue==value&&revision==renderRevision)return this;
            boundValue=value;revision=renderRevision;arrangedWidth=-1;main.setFont(list.getFont());
            String kind=value.getOrDefault("kind","info");name=value.getOrDefault("name","");
            symbol.setIcon(JavaCompletionIcons.forItem(value,fontSize+2));
            String label=value.getOrDefault("label",name);
            if(name.isEmpty())name=label;
            generics=value.getOrDefault("typeParameters","");
            call=kind.equals("method")||kind.equals("constructor");
            args=splitParameters(value.getOrDefault("parameters",""));
            active=parameters?Integer.parseInt(value.getOrDefault("activeParameter","-1")):-1;
            inlineDetail=kind.equals("class")||kind.equals("interface")||kind.equals("constructor")||kind.equals("postfix");
            String visibleText=label+generics+value.getOrDefault("parameters","");
            if(main.getFont().canDisplayUpTo(visibleText)>=0)main.setFont(new Font(Font.DIALOG,Font.PLAIN,fontSize));
            main.setForeground(TEXT);
            context=value.getOrDefault("detail","");if(value.containsKey("origin"))context=value.get("origin")+" · "+context;
            if(kind.equals("postfix"))context=value.getOrDefault("detail",value.getOrDefault("insert","")).replace('\n',' ');
            // Use Swing's UI font for descriptions/packages so Chinese text never becomes missing glyph boxes.
            detail.setFont(new Font(Font.DIALOG,Font.PLAIN,Math.max(12,fontSize-1)));
            detail.setText(context);detail.setForeground(MUTED);detail.setHorizontalAlignment(SwingConstants.LEADING);
            setToolTipText(label+generics+(value.containsKey("origin")?" · "+value.get("origin"):""));
            return this;
        }
        int naturalWidth(){return fontSize+38+Math.min(mainWidth(presentation(0,args.length)),fontSize*44)+(context.isEmpty()?0:12+detailWidth());}
        int fullWidth(){return fontSize+34+mainWidth(presentation(0,args.length))+(context.isEmpty()?0:12+detailWidth());}
        private int detailWidth(){return detail.getFontMetrics(detail.getFont()).stringWidth(context)+2;}
        @Override public Dimension getPreferredSize(){int width=layoutWidth>0?layoutWidth:cellWidth;return new Dimension(width,arrange(width));}
        @Override public void doLayout(){arrange(getWidth());}
        private int arrange(int width) {
            if(arrangedWidth==width)return arrangedHeight;
            int start=fontSize+26,right=Math.max(start,width-8),available=Math.max(1,right-start),gap=context.isEmpty()?0:12;
            int tail=context.isEmpty()?0:detailWidth();
            // On a narrow display a package may itself be wider than the screen. Wrap that full
            // detail below the name instead of losing it or introducing horizontal scrolling.
            boolean wrapped=tail+gap+Math.min(mainWidth(presentation(0,args.length)),fontSize*5)>available;
            int budget=Math.max(1,wrapped?available:available-tail-gap);
            Presentation shown=expanded?presentation(0,args.length):fit(budget);main.setText(expanded?shown.wrappedHtml(budget):shown.html());
            int mainWidth=Math.min(budget,mainWidth(shown)),lineHeight=Math.max(fontSize+6,main.getPreferredSize().height);
            int height=Math.max(fontSize+16,lineHeight+10);
            main.setBounds(start,5,mainWidth,lineHeight);
            if(wrapped&&!context.isEmpty()) {
                detail.setText(wrap(context,detail.getFontMetrics(detail.getFont()),available));
                int detailHeight=detail.getPreferredSize().height;
                detail.setBounds(start,height-2,available,detailHeight);height+=detailHeight+4;
            } else {
                detail.setText(context);
                detail.setBounds(inlineDetail?start+mainWidth+gap:right-tail,5,tail,lineHeight);
            }
            symbol.setBounds(4,5,fontSize+4,lineHeight);arrangedWidth=width;arrangedHeight=height;return height;
        }
        private int mainWidth(Presentation text) {
            FontMetrics normal=main.getFontMetrics(main.getFont());int width=normal.stringWidth(text.text)+2;
            if(text.activeStart>=0){String current=text.text.substring(text.activeStart,text.activeEnd);width+=main.getFontMetrics(main.getFont().deriveFont(Font.BOLD)).stringWidth(current)-normal.stringWidth(current);}
            return width;
        }
        private Presentation fit(int width) {
            Presentation text=presentation(0,args.length);
            if(mainWidth(text)<=width)return text;
            if(call&&active>0&&active<args.length) {
                // Keep the current parameter in view in Ctrl+P, then fill all remaining pixels
                // with the signature text. Never drop an entire generic argument just to fit.
                for(int from=0;from<=active;from++) {
                    text=presentation(from,args.length);
                    if(mainWidth(new Presentation(text.text.substring(0,text.activeEnd)+"...)",text.activeStart,text.activeEnd))<=width)break;
                }
            }
            String suffix=call?"...)":"...";int low=0,high=text.text.length();
            while(low<high){int end=(low+high+1)/2;Presentation clipped=text.prefix(end,suffix);if(mainWidth(clipped)<=width)low=end;else high=end-1;}
            if(low>0&&low<text.text.length()&&Character.isHighSurrogate(text.text.charAt(low-1)))low--;
            return text.prefix(low,suffix);
        }
        private Presentation presentation(int from,int to) {
            if(!call)return new Presentation(name+generics,-1,-1);
            StringBuilder text=new StringBuilder(name).append('(');int begin=-1,end=-1;
            if(from>0)text.append("...");
            for(int i=from;i<to;i++) {
                if(i>from||from>0)text.append(", ");if(i==active)begin=text.length();text.append(args[i]);if(i==active)end=text.length();
            }
            if(to<args.length){if(to>from||from>0)text.append(", ");text.append("...");}
            return new Presentation(text.append(')').toString(),begin,end);
        }
        private final class Presentation {
            final String text;final int activeStart,activeEnd;
            Presentation(String text,int start,int end){this.text=text;activeStart=start;activeEnd=end;}
            Presentation prefix(int end,String suffix){return new Presentation(text.substring(0,end)+suffix,activeStart>=0&&activeStart<end?activeStart:-1,activeStart>=0&&activeStart<end?Math.min(end,activeEnd):-1);}
            String html(){return "<html>"+segment(0,text.length())+"</html>";}
            String segment(int start,int end) {
                StringBuilder html=new StringBuilder();int at=start;
                int named=Math.min(name.length(),text.length()),matched=!prefix.isEmpty()&&text.regionMatches(true,0,prefix,0,prefix.length())?Math.min(prefix.length(),named):0;
                while(at<end) {
                    boolean current=at>=activeStart&&at<activeEnd;int next=end;
                    for(int boundary:new int[]{matched,named,activeStart,activeEnd})if(boundary>at)next=Math.min(next,boundary);
                    String color=current||at<matched?"#3569e1":at<named?"#23262e":"#707685";
                    html.append(current?"<b>":"").append("<font color='").append(color).append("'>").append(escape(text.substring(at,next))).append("</font>").append(current?"</b>":"");at=next;
                }
                return html.toString();
            }
            String wrappedHtml(int width) {
                if(mainWidth(this)<=width)return html();
                StringBuilder html=new StringBuilder("<html>");int start=0;
                while(start<text.length()) {
                    int end=start,next=start,lastBreak=-1;
                    while(next<text.length()) {
                        next=text.offsetByCodePoints(next,1);
                        String part=text.substring(start,next);int a=activeStart<next&&activeEnd>start?Math.max(0,activeStart-start):-1,b=a<0?-1:Math.min(next,activeEnd)-start;
                        if(mainWidth(new Presentation(part,a,b))>width&&end>start)break;
                        end=next;if(Character.isWhitespace(text.charAt(end-1))||text.charAt(end-1)==',')lastBreak=end;
                    }
                    if(end<text.length()&&lastBreak>start)end=lastBreak;
                    html.append(segment(start,end));if(end<text.length())html.append("<br>");start=end;
                }
                return html.append("</html>").toString();
            }
        }
        @Override protected void paintComponent(Graphics graphics){if(selected){Graphics2D g=(Graphics2D)graphics.create();g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);g.setColor(SELECTED);g.fillRoundRect(0,1,getWidth(),getHeight()-2,8,8);g.dispose();}super.paintComponent(graphics);}
    }
    /** Keep label painting on the same metric grid used by Swing HTML measurement, regardless
     * of rendering hints inherited from the surrounding editor. */
    private static final class LookupLabel extends JLabel {
        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g=(Graphics2D)graphics.create();
            try {g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS,getFontMetrics(getFont()).getFontRenderContext().getFractionalMetricsHint());super.paintComponent(g);}
            finally {g.dispose();}
        }
    }
    private static String[] splitParameters(String text) {
        if(text.isEmpty())return new String[0];java.util.List<String> args=new ArrayList<>();int depth=0,start=0;
        for(int i=0;i<text.length();i++){char c=text.charAt(i);if(c=='<')depth++;else if(c=='>')depth--;else if(c==','&&depth==0){args.add(text.substring(start,i).trim());start=i+1;}}
        args.add(text.substring(start).trim());return args.toArray(new String[0]);
    }
    private static String wrap(String text,FontMetrics metrics,int width) {
        StringBuilder html=new StringBuilder("<html>");int start=0,end=0;
        while(end<text.length()) {
            int next=text.offsetByCodePoints(end,1);
            if(end>start&&metrics.stringWidth(text.substring(start,next))+2>width){html.append(escape(text.substring(start,end))).append("<br>");start=end;}
            end=next;
        }
        return html.append(escape(text.substring(start))).append("</html>").toString();
    }
    private static String escape(String text){return text.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;");}
}
