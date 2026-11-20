package cn.lucanet.enhancement.exit;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;
import javax.swing.*;

public final class JavaCompletionPopupTest {
    public static void main(String[] args)throws Exception {
        EventQueue.invokeAndWait(()->{
            try {
                String[] names={"class","classAbstract","interface","enum","annotation","record","exception","abstractException","field","variable","parameter","method","methodAbstract","template"};
                BufferedImage sheet=new BufferedImage(960,390,BufferedImage.TYPE_INT_RGB);Graphics2D g=sheet.createGraphics();g.setColor(Color.WHITE);g.fillRect(0,0,960,390);
                for(int i=0;i<names.length;i++) {
                    Map<String,String> row=new HashMap<>();row.put("icon",names[i]);
                    int x=10+(i%7)*136,y=10+(i/7)*125;
                    for(int size:new int[]{14,20,32}) {
                        Icon icon=JavaCompletionIcons.forItem(row,size);check(icon!=null&&icon.getIconWidth()==size,"bundled icon dimensions");
                        BufferedImage rendered=new BufferedImage(size,size,BufferedImage.TYPE_INT_ARGB);Graphics2D p=rendered.createGraphics();icon.paintIcon(null,p,0,0);p.dispose();
                        int opaque=0;for(int a=0;a<size;a++)for(int b=0;b<size;b++)if(rendered.getRGB(a,b)>>>24!=0)opaque++;
                        check(opaque>size,"official icon actually renders: "+names[i]);g.drawImage(rendered,x,y,null);x+=size+10;
                    }
                    g.setColor(Color.DARK_GRAY);g.setFont(new Font(Font.DIALOG,Font.PLAIN,13));g.drawString(names[i],10+(i%7)*136,y+55);
                }
                Map<String,String> constant=new HashMap<>();constant.put("icon","field");constant.put("static","true");constant.put("final","true");
                JavaCompletionIcons.forItem(constant,32).paintIcon(null,g,20,275);g.drawString("static final field",65,298);
                Map<String,String> template=new HashMap<>();template.put("kind","postfix");check(JavaCompletionIcons.forItem(template,20)!=null,"postfix uses official template icon");
                check(JavaCompletionIcons.forItem(Collections.singletonMap("kind","keyword"),20)==null,"keyword has no invented icon");
                DefaultListModel<Map<String,String>> model=new DefaultListModel<>();JList<Map<String,String>> list=new JList<>(model);JavaCompletionPopup popup=new JavaCompletionPopup(list);
                Map<String,String> custom=new HashMap<>();custom.put("kind","class");custom.put("name","中文工具");custom.put("label","中文工具");custom.put("origin","当前脚本 Class");custom.put("detail","com.lucanet.common.dom.script.rt");
                Component renderer=list.getCellRenderer().getListCellRendererComponent(list,custom,0,true,false);
                for(Component component:((Container)renderer).getComponents())if(component instanceof JLabel){
                    JLabel label=(JLabel)component;String text=label.getText();if(text!=null&&text.contains("当前脚本"))check(label.getFont().canDisplayUpTo("当前脚本")<0,"origin label has Chinese glyphs");
                    if(text!=null&&text.contains("中文工具"))check(label.getFont().canDisplayUpTo("中文工具")<0,"Chinese identifier font fallback");
                }
                renderer.setSize(880,38);layout((Container)renderer);g.translate(20,310);renderer.printAll(g);g.dispose();
                Path output=Path.of(args[0]);Files.createDirectories(output.getParent());ImageIO.write(sheet,"png",output.toFile());
                verifyLayout(output.resolveSibling("java-lookup-layout.png"));
                verifyExpansion(output.resolveSibling("java-selected-signature.png"));
            }catch(Exception failure){throw new RuntimeException(failure);}
        });
        System.out.println("PASS: official icons, generic labels, alignment, pixel-filling elision and complete selected signatures at 12/18/30 px, including narrow screens");
    }
    private static void verifyLayout(Path output)throws Exception {
        DefaultListModel<Map<String,String>> model=new DefaultListModel<>();JList<Map<String,String>> list=new JList<>(model);JavaCompletionPopup popup=new JavaCompletionPopup(list);
        Map<String,String> simple=item("interface","List","java.util");simple.put("typeParameters","<E>");
        Map<String,String> longPackage=item("class","List","org.apache.xmlbeans.impl.xb.xsdschema.ListDocument");
        Map<String,String> method=item("method","of","List<E>");method.put("parameters","E arg0, E arg1, E arg2, E arg3, E arg4, E arg5, E arg6, E arg7, E arg8, E arg9");method.put("activeParameter","8");method.put("label","of("+method.get("parameters")+") : List<E>");
        Map<String,String> huge=item("class","LongCandidate".repeat(20),"java.util");
        BufferedImage image=new BufferedImage(1000,650,BufferedImage.TYPE_INT_RGB);Graphics2D g=image.createGraphics();g.setColor(Color.WHITE);g.fillRect(0,0,image.getWidth(),image.getHeight());int y=12;
        for(int size:new int[]{12,18,30}) {
            model.clear();for(Map<String,String> value:Arrays.asList(simple,longPackage,method,huge))model.addElement(value);
            popup.fontSize(size);popup.content("Li",false);popup.setSize(popup.getPreferredSize());layout(popup);
            JScrollPane scroll=(JScrollPane)popup.getComponent(0);
            check(scroll.getHorizontalScrollBarPolicy()==ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER&&!scroll.getHorizontalScrollBar().isVisible(),"no horizontal scrollbar");
            check(list.getWidth()<=scroll.getViewport().getWidth(),"long candidate cannot expand list beyond viewport");
            for(int i=0;i<3;i++) {
                Component row=render(list,model.get(i));JLabel main=label(row,"lookupMain"),detail=label(row,"lookupDetail");
                check(main.getX()+main.getWidth()<=row.getWidth()&&detail.getX()+detail.getWidth()<=row.getWidth(),"both columns stay in viewport");
                check(plain(detail.getText()).equals(model.get(i).get("detail")),"entire package/type retained");
                check(detail.getPreferredSize().width<=detail.getWidth(),"detail has enough width to paint completely");
                if(i==0){check(main.getText().contains("&lt;E&gt;"),"generic suffix is rendered");check(detail.getX()==main.getX()+main.getWidth()+12,"short package directly follows class");}
                if(i==1&&detail.getY()==main.getY())check(detail.getX()==main.getX()+main.getWidth()+12,"long package is left aligned");
                if(i==2){check(main.getText().contains("..."),"long method arguments explicitly abbreviated");check(detail.getX()+detail.getWidth()==row.getWidth()-8,"return type right aligned");check(row instanceof JComponent&&((JComponent)row).getToolTipText().contains("arg9"),"tooltip keeps full signature");}
                Graphics2D cell=(Graphics2D)g.create();cell.translate(12,y);row.printAll(cell);cell.dispose();y+=row.getHeight()+4;
            }
            popup.content("",true);Component row=render(list,method);JLabel main=label(row,"lookupMain");
            check(main.getText().contains("<b>")&&main.getText().contains("arg8"),"abbreviated parameter hint keeps current argument visible");
            Graphics2D cell=(Graphics2D)g.create();cell.translate(12,y);row.printAll(cell);cell.dispose();y+=row.getHeight()+16;
        }
        // Even a package longer than the available monitor width stays readable by wrapping.
        Map<String,String> narrow=item("class","List","com.example.".repeat(14)+"Types");model.clear();model.addElement(narrow);popup.fontSize(30);popup.content("",false);
        Component row=render(list,narrow);JLabel main=label(row,"lookupMain"),detail=label(row,"lookupDetail");
        check(detail.getY()>=main.getY()+main.getHeight(),"oversized detail wraps below name");
        check(plain(detail.getText()).equals(narrow.get("detail")),"wrapped full package retains every character");
        check(detail.getPreferredSize().width<=detail.getWidth()&&detail.getPreferredSize().height<=detail.getHeight(),"wrapped label bounds match all lines");
        g.dispose();ImageIO.write(image,"png",output.toFile());
    }
    private static Map<String,String> item(String kind,String name,String detail){Map<String,String> value=new HashMap<>();value.put("kind",kind);value.put("name",name);value.put("label",name+" — "+detail);value.put("detail",detail);return value;}
    private static void verifyExpansion(Path output)throws Exception {
        DefaultListModel<Map<String,String>> model=new DefaultListModel<>();JList<Map<String,String>> list=new JList<>(model);JavaCompletionPopup popup=new JavaCompletionPopup(list);
        Map<String,String> value=item("method","computeIfAbsent","String");value.put("parameters","String key, Function<? super String, ? extends String> mappingFunction");value.put("label",value.get("name")+"("+value.get("parameters")+") : String");model.addElement(value);
        Map<String,String> other=item("method","computeIfPresent","String");other.put("parameters","String arg0, BiFunction<? super String, ? super String, ? extends String> arg1");model.addElement(other);
        java.lang.reflect.Method expand=JavaCompletionPopup.class.getDeclaredMethod("createExpansion",int.class,int.class);expand.setAccessible(true);
        BufferedImage image=new BufferedImage(1600,800,BufferedImage.TYPE_INT_RGB);Graphics2D g=image.createGraphics();g.setColor(Color.WHITE);g.fillRect(0,0,image.getWidth(),image.getHeight());int y=12;
        for(int size:new int[]{12,18,30}) {
            popup.fontSize(size);popup.content("com",false);popup.setSize(popup.getPreferredSize());layout(popup);list.setSelectedIndex(0);
            Component compact=render(list,value);JLabel compactMain=label(compact,"lookupMain"),compactDetail=label(compact,"lookupDetail");
            String shown=plain(compactMain.getText());check(shown.contains(size<30?"Function<? super String":"Function<"),"compact row keeps the start of a long generic parameter: "+shown);
            check(shown.contains("...")&&shown.endsWith(")"),"only the overflowing tail is abbreviated");
            int used=compactMain.getFontMetrics(compactMain.getFont()).stringWidth(shown);
            check(compactDetail.getX()-12-compactMain.getX()-used<compactMain.getFontMetrics(compactMain.getFont()).charWidth('W')+4,"compact signature uses remaining width instead of dropping whole argument");
            paintRow(g,compact,y);y+=compact.getHeight()+6;
            JComponent expanded=(JComponent)expand.invoke(popup,1550,700);check(expanded!=null,"selected long signature has preview");expanded.setSize(expanded.getPreferredSize());layout(expanded);
            check(expand.invoke(popup,1550,700)==expanded,"unchanged expanded signature reuses the prepared view");
            JLabel full=label(expanded,"lookupMain"),detail=label(expanded,"lookupDetail");
            check(plain(full.getText()).equals("computeIfAbsent("+value.get("parameters")+")"),"selection includes every parameter without ellipsis");
            check(full.getPreferredSize().width<=full.getWidth()&&full.getPreferredSize().height<=full.getHeight(),"expanded signature paints in bounds");
            check(java.util.Arrays.equals(pixels(full,false),pixels(full,true)),"parent fractional metrics cannot clip the final parameter/parenthesis");
            check(detail.getText().equals("String")&&detail.getPreferredSize().width<=detail.getWidth(),"expanded return type fully visible");
            if(size<30)check(!full.getText().contains("<br>"),"sufficient screen width expands a single row");
            paintRow(g,expanded,y);y+=expanded.getHeight()+16;
            list.setSelectedIndex(1);JComponent switched=(JComponent)expand.invoke(popup,700,650);switched.setSize(switched.getPreferredSize());layout(switched);
            JLabel changed=label(switched,"lookupMain");check(plain(changed.getText()).equals("computeIfPresent("+other.get("parameters")+")"),"selection changes the full signature");
            check(changed.getPreferredSize().width<=changed.getWidth(),"narrow monitor wraps full arguments instead of clipping");
            if(size>=18)check(changed.getText().contains("<br>"),"signature exceeding screen width wraps");
            list.setSelectedIndex(0);check(expand.invoke(popup,1550,700)==expanded,"returning to a candidate reuses its full signature");
        }
        popup.fontSize(18);popup.content("",true);value.put("activeParameter","1");list.setSelectedIndex(0);
        JComponent parameters=(JComponent)expand.invoke(popup,650,650);parameters.setSize(parameters.getPreferredSize());layout(parameters);
        JLabel main=label(parameters,"lookupMain");check(main.getText().contains("<b>")&&plain(main.getText()).equals("computeIfAbsent("+value.get("parameters")+")"),"expanded Ctrl+P preserves full active generic argument and highlight");
        int[] clicks={0};list.addMouseListener(new java.awt.event.MouseAdapter(){@Override public void mouseClicked(java.awt.event.MouseEvent e){if(e.getClickCount()==2)clicks[0]++;}});
        main.dispatchEvent(new java.awt.event.MouseEvent(main,java.awt.event.MouseEvent.MOUSE_CLICKED,0,0,2,2,2,false,java.awt.event.MouseEvent.BUTTON1));check(clicks[0]==1,"double click on expanded label reaches original lookup");
        CellRendererPane oldPane=new CellRendererPane();Component oldRow=render(list,value);oldPane.add(oldRow);
        value.put("parameters","String changed");popup.content("",false);
        check(oldRow.getParent()==null&&oldPane.getComponentCount()==0,"replaced candidates release old renderer documents");
        check(expand.invoke(popup,650,650)==null,"fresh content invalidates cached long signature");
        value.put("parameters","String key, Function<? super String, ? extends String> changedFunction");popup.content("",false);
        JComponent refreshed=(JComponent)expand.invoke(popup,650,650);check(refreshed!=parameters,"changed source gets a fresh view");
        check(plain(label(refreshed,"lookupMain").getText()).contains("changedFunction"),"cached expansion never shows previous source parameters");
        g.dispose();ImageIO.write(image,"png",output.toFile());
    }
    private static void paintRow(Graphics2D g,Component row,int y){Graphics2D cell=(Graphics2D)g.create();cell.translate(12,y);row.printAll(cell);cell.dispose();}
    private static int[] pixels(JLabel label,boolean fractional){BufferedImage image=new BufferedImage(label.getWidth(),label.getHeight(),BufferedImage.TYPE_INT_ARGB);Graphics2D g=image.createGraphics();g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS,fractional?RenderingHints.VALUE_FRACTIONALMETRICS_ON:RenderingHints.VALUE_FRACTIONALMETRICS_OFF);label.printAll(g);g.dispose();return image.getRGB(0,0,image.getWidth(),image.getHeight(),null,0,image.getWidth());}
    private static String plain(String text){return text.startsWith("<html>")?text.replaceAll("<[^>]*>","").replace("&lt;","<").replace("&gt;",">").replace("&amp;","&"):text;}
    private static Component render(JList<Map<String,String>> list,Map<String,String> value){Component row=list.getCellRenderer().getListCellRendererComponent(list,value,0,true,false);Dimension size=row.getPreferredSize();if(list.getWidth()>0)size.width=list.getWidth();row.setSize(size);layout((Container)row);return row;}
    private static JLabel label(Component row,String name){JLabel found=findLabel(row,name);if(found==null)throw new AssertionError(name);return found;}
    private static JLabel findLabel(Component row,String name){if(name.equals(row.getName()))return (JLabel)row;if(row instanceof Container)for(Component child:((Container)row).getComponents()){JLabel found=findLabel(child,name);if(found!=null)return found;}return null;}
    private static void layout(Container container){container.doLayout();for(Component child:container.getComponents())if(child instanceof Container)layout((Container)child);}
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
