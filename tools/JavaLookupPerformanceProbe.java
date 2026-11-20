package cn.lucanet.enhancement.exit;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.lang.reflect.Method;
import java.nio.file.*;
import java.util.*;
import javax.swing.*;

/** Same rendering workload for before/after comparison; no installed Lucanet/session involved. */
public final class JavaLookupPerformanceProbe {
    public static void main(String[] args)throws Exception {
        StringBuilder result=new StringBuilder();
        EventQueue.invokeAndWait(()->{try {
            DefaultListModel<Map<String,String>> model=new DefaultListModel<>();
            for(int i=0;i<32;i++) {
                Map<String,String> value=new HashMap<>();value.put("kind","method");value.put("name","computeValue"+i);
                value.put("parameters","String key, BiFunction<? super String, ? super java.util.List<String>, ? extends java.util.Map<String, java.util.List<String>>> mappingFunction, boolean keepExisting");
                value.put("detail","Map<String, List<String>>");value.put("label",value.get("name")+"("+value.get("parameters")+")");model.addElement(value);
            }
            JList<Map<String,String>> list=new JList<>(model);JavaCompletionPopup popup=new JavaCompletionPopup(list);
            Method expand=JavaCompletionPopup.class.getDeclaredMethod("createExpansion",int.class,int.class);expand.setAccessible(true);
            BufferedImage canvas=new BufferedImage(1500,750,BufferedImage.TYPE_INT_RGB);Graphics2D graphics=canvas.createGraphics();
            for(int size:new int[]{18,30}) {
                long begin=System.nanoTime();popup.fontSize(size);popup.content("com",false);popup.setSize(popup.getPreferredSize());layout(popup);
                result.append("font=").append(size).append(" open_ms=").append(ms(System.nanoTime()-begin)).append('\n');
                for(int i=0;i<32;i++)step(list,popup,expand,graphics,i);
                long[] samples=new long[180];
                for(int i=0;i<samples.length;i++){long t=System.nanoTime();step(list,popup,expand,graphics,(i<90?i:180-i)%32);samples[i]=System.nanoTime()-t;}
                Arrays.sort(samples);result.append("selection_render_ms median=").append(ms(samples[90])).append(" p95=").append(ms(samples[171])).append(" max=").append(ms(samples[179])).append('\n');
            }
            graphics.dispose();popup.setVisible(false);
        }catch(Exception failure){throw new RuntimeException(failure);}});
        System.out.print(result);if(args.length>0)Files.writeString(Path.of(args[0]),result);
    }
    private static void step(JList<Map<String,String>> list,JavaCompletionPopup popup,Method expand,Graphics2D graphics,int index)throws Exception {
        list.setSelectedIndex(index);list.ensureIndexIsVisible(index);
        for(int i=Math.max(0,index-4);i<Math.min(list.getModel().getSize(),index+5);i++) {
            Component row=list.getCellRenderer().getListCellRendererComponent(list,list.getModel().getElementAt(i),i,i==index,false);
            row.setSize(list.getWidth(),row.getPreferredSize().height);layout((Container)row);row.printAll(graphics);
        }
        JComponent view=(JComponent)expand.invoke(popup,1450,700);
        if(view!=null){view.setSize(view.getPreferredSize());layout(view);view.printAll(graphics);}
    }
    private static double ms(long ns){return Math.round(ns/1000.0)/1000.0;}
    private static void layout(Container c){c.doLayout();for(Component child:c.getComponents())if(child instanceof Container)layout((Container)child);}
}
