package cn.lucanet.enhancement.exit;

import java.awt.*;
import java.util.*;
import java.util.List;
import javax.swing.*;
import javax.swing.tree.*;

/** The native details/source/info tabs stay in place. Full units have one authoritative editor. */
final class CustomJavaDetails {
    static final String HANDLER="cn.lucanet.enhancement.javaSource.v1.details";
    static final String RENAME="cn.lucanet.enhancement.javaSource.v1.rename";
    private static final String SNAPSHOT=HANDLER+".snapshot";
    static boolean show(Object value) {
        try {
            Object code=CustomJavaModel.field(value,CustomJavaModel.CODE);
            Object owner=CustomJavaModel.field(code,"com.lucanet.common.dom.script.elements.ScriptElement");
            if(!CustomJavaModel.isClass(owner))return false;
            Map<String,String> data=CustomJavaModel.attributes(owner).get(CustomJavaModel.ATTRIBUTE);
            if(data==null||!data.containsKey("source"))return false;
            CustomJavaModel.Version version=CustomJavaModel.version(code,data);
            if(!version.editableAsSource())return false;
            JComponent panel=(JComponent)value;
            Object previous=panel.getClientProperty(SNAPSHOT);
            if(previous instanceof CustomJavaModel.Version&&version.sameAs((CustomJavaModel.Version)previous))return true;
            DefaultMutableTreeNode root=new DefaultMutableTreeNode(version.qualifiedName());
            List<DefaultMutableTreeNode> parents=new ArrayList<>();parents.add(root);
            for(String line:version.outline().split("\n")) {
                int tab=line.indexOf('\t');if(tab<0)continue;
                int depth=Math.min(Integer.parseInt(line.substring(0,tab)),parents.size()-1);
                DefaultMutableTreeNode row=new DefaultMutableTreeNode(line.substring(tab+1));parents.get(depth).add(row);
                while(parents.size()>depth+1)parents.remove(parents.size()-1);parents.add(row);
            }
            JTree tree=new JTree(root);tree.setName("customJavaStructure");tree.setFont(new Font(Font.MONOSPACED,Font.PLAIN,14));tree.setRowHeight(26);
            for(int row=0;row<tree.getRowCount();row++)tree.expandRow(row);
            JPanel content=new JPanel(new BorderLayout(0,10));content.setBorder(BorderFactory.createEmptyBorder(12,12,12,12));
            CustomJavaModel.StatusText status=new CustomJavaModel.StatusText();
            status.setName("customJavaDetailsStatus");
            version.status().show(status);
            content.add(status,BorderLayout.NORTH);
            content.add(new JScrollPane(tree));panel.removeAll();panel.setLayout(new BorderLayout());panel.add(content);panel.revalidate();panel.repaint();
            panel.putClientProperty(SNAPSHOT,version);
            return true;
        }catch(Throwable failure){EnhancementLog.error("CUSTOM_JAVA_DETAILS_FAILED",failure);return false;}
    }
    static List<String> renameError(Object controller) {
        try {
            Object node=CustomJavaModel.field(controller,CustomJavaModel.CLASS);
            if(CustomJavaModel.storedSource(node)!=null)return List.of("请双击 Class 节点，在完整源码窗口修改类型名称并保存。");
        }catch(Throwable failure){EnhancementLog.error("CUSTOM_JAVA_RENAME_CHECK_FAILED",failure);}
        return null;
    }
}
