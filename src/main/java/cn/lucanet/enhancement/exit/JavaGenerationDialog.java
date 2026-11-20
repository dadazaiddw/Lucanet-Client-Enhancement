package cn.lucanet.enhancement.exit;

import java.awt.*;
import java.awt.event.*;
import java.util.*;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.*;
import javax.swing.event.*;
import javax.swing.text.JTextComponent;

/** IDEA-style member selection and Objects wizard. No document edits until Finish. */
final class JavaGenerationDialog {
    private static final Color BACKGROUND=new Color(247,248,250),TEXT=new Color(31,33,38),LINE=new Color(216,219,226),BLUE=new Color(53,116,240);
    static JDialog create(JTextComponent editor,List<Map<String,String>> rows,int size,Consumer<Map<String,Object>> generate){
        return new Wizard(editor,rows,size,generate).dialog;
    }

    private static final class Wizard {
        final JDialog dialog;
        final Map<String,String> metadata;
        final String kind;
        final List<Map<String,String>> fields=new ArrayList<>(),parents=new ArrayList<>();
        final Consumer<Map<String,Object>> generate;
        final JPanel content=panel(new BorderLayout(0,16)),page=panel(new BorderLayout(0,14));
        final JButton back=button("Back",false),next=button("OK",true),cancel=button("Cancel",false);
        final JLabel progress=new JLabel();
        final JCheckBox getters=check("Use getters when available"),withSuper=check("Call superclass methods"),override=check("Insert @Override");
        final JRadioButton getClass=new JRadioButton("getClass()",true),instanceofCheck=new JRadioButton("instanceof");
        final FieldPicker selected,hash,nonNull;
        final JList<Map<String,String>> constructors;
        int step;

        Wizard(JTextComponent editor,List<Map<String,String>> rows,int size,Consumer<Map<String,Object>> generate){
            this.generate=generate;metadata=rows.get(0);kind=metadata.get("name");
            Window owner=SwingUtilities.getWindowAncestor(editor);
            dialog=new JDialog(owner,dialogTitle(kind),Dialog.ModalityType.DOCUMENT_MODAL);
            dialog.setName("customJavaGeneration");dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
            if(owner!=null)dialog.setIconImages(owner.getIconImages());
            for(Map<String,String> row:rows){if("field".equals(row.get("kind")))fields.add(row);if("superConstructor".equals(row.get("kind")))parents.add(row);}
            selected=new FieldPicker(fields,metadata.get("owner"),true);selected.list.setName("customJavaGenerationFields");
            hash=new FieldPicker(fields,metadata.get("owner"),true);hash.list.setName("customJavaHashFields");
            nonNull=new FieldPicker(referenceFields(),metadata.get("owner"),false);nonNull.list.setName("customJavaNonNullFields");
            constructors=new JList<>(new Vector<>(parents));constructors.setName("customJavaSuperConstructor");
            constructors.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);styleList(constructors);constructors.setCellRenderer(renderer(false));
            if(!parents.isEmpty())constructors.setSelectedIndex(0);
            getters.setName("customJavaGenerationGetters");getters.setEnabled(fields.stream().anyMatch(f->Boolean.parseBoolean(f.get("getter"))));
            withSuper.setName("customJavaGenerationSuper");withSuper.setVisible(Boolean.parseBoolean(metadata.get("superAvailable")));
            override.setName("customJavaGenerationOverride");override.setSelected(true);
            getClass.setName("customJavaGenerationGetClass");instanceofCheck.setName("customJavaGenerationInstanceof");
            getClass.setOpaque(false);instanceofCheck.setOpaque(false);ButtonGroup checks=new ButtonGroup();checks.add(getClass);checks.add(instanceofCheck);
            content.setBorder(BorderFactory.createEmptyBorder(18,20,14,20));content.setFont(new Font(Font.DIALOG,Font.PLAIN,size));content.add(page);
            JPanel footer=panel(new BorderLayout());footer.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(1,0,0,0,LINE),BorderFactory.createEmptyBorder(14,0,0,0)));
            progress.setForeground(new Color(112,117,128));progress.setName("customJavaGenerationStep");footer.add(progress,BorderLayout.WEST);
            JPanel buttons=panel(new FlowLayout(FlowLayout.RIGHT,8,0));buttons.add(cancel);buttons.add(back);buttons.add(next);footer.add(buttons,BorderLayout.EAST);content.add(footer,BorderLayout.SOUTH);
            back.setName("customJavaGenerationBack");cancel.setName("customJavaGenerationCancel");
            back.addActionListener(e->{step--;showPage();});next.addActionListener(e->advance());cancel.addActionListener(e->dialog.dispose());
            dialog.setContentPane(content);dialog.getRootPane().setDefaultButton(next);
            dialog.getRootPane().registerKeyboardAction(e->dialog.dispose(),KeyStroke.getKeyStroke("ESCAPE"),JComponent.WHEN_IN_FOCUSED_WINDOW);
            dialog.addWindowListener(new WindowAdapter(){@Override public void windowOpened(WindowEvent e){focusPage();}});
            showPage();
        }
        boolean equality(){return kind.equals("equalsHashCode");}
        boolean parentStep(){return kind.equals("constructor")&&parents.size()>1;}
        boolean last(){return equality()?step==3||step==2&&referenceFields().isEmpty():!parentStep()||step==1;}
        List<Map<String,String>> referenceFields(){List<Map<String,String>> result=new ArrayList<>();for(Map<String,String> f:fields)if(!Boolean.parseBoolean(f.get("primitive"))&&selected.names().contains(f.get("name")))result.add(f);return result;}
        void showPage(){
            page.removeAll();Component body;
            if(equality()&&step==0){
                JPanel options=panel(new GridLayout(0,1,0,10));options.add(template("java.util.Objects"));
                options.add(new JLabel("For class type comparison in equals() generate:"));options.add(getClass);options.add(instanceofCheck);
                options.add(getters);if(withSuper.isVisible())options.add(withSuper);
                JPanel holder=new JPanel(new BorderLayout()){@Override public Dimension getPreferredSize(){Dimension size=super.getPreferredSize();return new Dimension(Math.max(580,size.width),Math.max(250,size.height));}};holder.setBackground(BACKGROUND);holder.add(options,BorderLayout.NORTH);body=holder;
            }else if(parentStep()&&step==0){
                page.add(new JLabel("Select Superclass Constructor"),BorderLayout.NORTH);
                JScrollPane scroll=new JScrollPane(constructors);scroll.setBorder(BorderFactory.createLineBorder(LINE));scroll.setPreferredSize(new Dimension(580,220));body=scroll;
            }else{
                FieldPicker picker=selected;String heading=fieldHeading(kind);
                if(equality()){
                    if(step==1)heading="Choose fields to be included in equals()";
                    else if(step==2){picker=hash;heading="Choose fields to be included in hashCode()";}
                    else {picker=nonNull;heading="Select all non-null fields";}
                }
                JPanel top=panel(new GridLayout(0,1,0,12));
                if(kind.equals("toString"))top.add(template("String concat (+)"));
                else if(Set.of("getter","setter","accessors").contains(kind))top.add(template("Default"));
                top.add(new JLabel(heading));page.add(top,BorderLayout.NORTH);body=picker;
                if(kind.equals("toString")){JPanel options=panel(new GridLayout(0,1,0,6));options.add(override);options.add(getters);if(withSuper.isVisible())options.add(withSuper);page.add(options,BorderLayout.SOUTH);}
                else if(equality()&&step==3){JLabel hint=new JLabel("Only select fields that never contain null.");hint.setForeground(new Color(112,117,128));page.add(hint,BorderLayout.SOUTH);}
            }
            page.add(body,BorderLayout.CENTER);back.setVisible(equality()||parentStep());back.setEnabled(step>0);
            next.setText(last()?(equality()?"Finish":"OK"):"Next");next.setName(last()?"customJavaGenerateMembers":"customJavaGenerationNext");
            progress.setText(equality()?"Step "+(step+1)+" of "+(step>=2&&referenceFields().isEmpty()?3:4):parentStep()?"Step "+(step+1)+" of 2":"");
            font(dialog,content.getFont().getSize());fit(dialog);EventQueue.invokeLater(this::focusPage);
        }
        void focusPage(){if(!dialog.isShowing())return;if(equality()&&step==0)getClass.requestFocusInWindow();else if(parentStep()&&step==0)constructors.requestFocusInWindow();else (equality()&&step==2?hash:equality()&&step==3?nonNull:selected).list.requestFocusInWindow();}
        void advance(){
            if(last()){
                Map<String,Object> options=new HashMap<>();options.put("selectedFields",selected.names());options.put("includeSuper",withSuper.isSelected());options.put("useGetters",getters.isSelected());options.put("insertOverride",override.isSelected());
                options.put("useInstanceof",instanceofCheck.isSelected());options.put("hashFields",hash.names());options.put("nonNullFields",nonNull.names());
                if(constructors.getSelectedValue()!=null)options.put("superConstructor",constructors.getSelectedValue().get("name"));
                dialog.dispose();generate.accept(options);return;
            }
            if(equality()&&step==1){List<Map<String,String>> available=new ArrayList<>();for(Map<String,String> f:fields)if(selected.names().contains(f.get("name")))available.add(f);hash.available(available,true);nonNull.available(referenceFields(),false);}
            step++;showPage();
        }
    }

    private static final class FieldPicker extends JPanel {
        final JList<Map<String,String>> list=new JList<>(new DefaultListModel<>());
        final JTextField search=new JTextField();
        final Set<String> selected=new LinkedHashSet<>();
        List<Map<String,String>> rows=new ArrayList<>();
        boolean updating;
        FieldPicker(List<Map<String,String>> fields,String owner,boolean all){
            super(new BorderLayout(0,8));setOpaque(false);styleList(list);list.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);list.setCellRenderer(renderer(true));
            search.setName("customJavaFieldSearch");search.setToolTipText("Filter fields by name or type");
            JPanel searchRow=panel(new BorderLayout(8,0));searchRow.add(new JLabel("Search:"),BorderLayout.WEST);searchRow.add(search);add(searchRow,BorderLayout.NORTH);
            JPanel tree=new JPanel(new BorderLayout());tree.setBackground(Color.WHITE);tree.setBorder(BorderFactory.createLineBorder(LINE));
            JLabel root=new JLabel(owner,JavaCompletionIcons.forItem(Map.of("icon","class"),18),SwingConstants.LEFT);root.setBorder(BorderFactory.createEmptyBorder(8,10,8,10));tree.add(root,BorderLayout.NORTH);
            JScrollPane scroll=new JScrollPane(list);scroll.setBorder(BorderFactory.createEmptyBorder());scroll.setPreferredSize(new Dimension(580,Math.min(300,Math.max(180,fields.size()*34))));tree.add(scroll);add(tree);
            JPanel footer=panel(new BorderLayout());JLabel hint=new JLabel("Ctrl / Shift: multi-select");hint.setForeground(new Color(112,117,128));footer.add(hint,BorderLayout.WEST);
            JPanel links=panel(new FlowLayout(FlowLayout.RIGHT,8,0));JButton selectAll=link("Select All"),none=link("Select None");links.add(selectAll);links.add(none);footer.add(links,BorderLayout.EAST);add(footer,BorderLayout.SOUTH);
            selectAll.addActionListener(e->{for(Map<String,String> row:rows)selected.add(row.get("name"));refresh();});none.addActionListener(e->{selected.clear();refresh();});
            list.addListSelectionListener(e->{if(updating||e.getValueIsAdjusting())return;for(int i=0;i<list.getModel().getSize();i++){String name=list.getModel().getElementAt(i).get("name");if(list.isSelectedIndex(i))selected.add(name);else selected.remove(name);}restoreRequired();});
            list.getInputMap().put(KeyStroke.getKeyStroke("SPACE"),"toggleField");list.getActionMap().put("toggleField",new AbstractAction(){public void actionPerformed(ActionEvent e){int i=list.getLeadSelectionIndex();if(i<0)return;if(list.isSelectedIndex(i))list.removeSelectionInterval(i,i);else list.addSelectionInterval(i,i);}});
            search.getDocument().addDocumentListener(new DocumentListener(){public void insertUpdate(DocumentEvent e){refresh();}public void removeUpdate(DocumentEvent e){refresh();}public void changedUpdate(DocumentEvent e){refresh();}});
            search.getInputMap().put(KeyStroke.getKeyStroke("DOWN"),"fields");search.getActionMap().put("fields",new AbstractAction(){public void actionPerformed(ActionEvent e){list.requestFocusInWindow();}});
            available(fields,all);
        }
        void available(List<Map<String,String>> available,boolean selectNew){Set<String> old=new HashSet<>();for(Map<String,String> r:rows)old.add(r.get("name"));Set<String> allowed=new HashSet<>();for(Map<String,String> r:available){String name=r.get("name");allowed.add(name);if(selectNew&&!old.contains(name))selected.add(name);}selected.retainAll(allowed);rows=new ArrayList<>(available);refresh();}
        List<String> names(){List<String> names=new ArrayList<>();for(Map<String,String> row:rows)if(selected.contains(row.get("name")))names.add(row.get("name"));return names;}
        void restoreRequired(){updating=true;try{for(Map<String,String> row:rows)if(Boolean.parseBoolean(row.get("required")))selected.add(row.get("name"));for(int i=0;i<list.getModel().getSize();i++)if(selected.contains(list.getModel().getElementAt(i).get("name")))list.addSelectionInterval(i,i);}finally{updating=false;}}
        void refresh(){updating=true;try{DefaultListModel<Map<String,String>> model=(DefaultListModel<Map<String,String>>)list.getModel();model.clear();String query=search.getText().toLowerCase(Locale.ROOT);for(Map<String,String> row:rows)if(row.get("label").toLowerCase(Locale.ROOT).contains(query))model.addElement(row);}finally{updating=false;}restoreRequired();}
    }

    private static String dialogTitle(String kind){switch(kind){case "constructor":return "Generate Constructor";case "getter":return "Select Fields to Generate Getters";case "setter":return "Select Fields to Generate Setters";case "accessors":return "Select Fields to Generate Getters and Setters";case "equalsHashCode":return "Generate equals() and hashCode()";default:return "Generate toString()";}}
    private static String fieldHeading(String kind){switch(kind){case "constructor":return "Select Fields to Initialize by Constructor";case "toString":return "Select Fields to Generate toString()";default:return dialogTitle(kind);}}
    private static JPanel panel(LayoutManager layout){JPanel panel=new JPanel(layout);panel.setBackground(BACKGROUND);return panel;}
    private static JCheckBox check(String title){JCheckBox box=new JCheckBox(title);box.setOpaque(false);return box;}
    private static JPanel template(String name){JPanel panel=panel(new BorderLayout(10,0));panel.add(new JLabel("Template:"),BorderLayout.WEST);JComboBox<String> choices=new JComboBox<>(new String[]{name});choices.setName("customJavaGenerationTemplate");choices.setBackground(Color.WHITE);panel.add(choices);return panel;}
    private static void styleList(JList<?> list){list.setBackground(Color.WHITE);list.setForeground(TEXT);list.setSelectionBackground(new Color(212,226,255));list.setSelectionForeground(TEXT);list.setBorder(BorderFactory.createEmptyBorder(2,6,6,6));}
    private static ListCellRenderer<Object> renderer(boolean field){return new DefaultListCellRenderer(){@Override public Component getListCellRendererComponent(JList<?> list,Object value,int index,boolean selected,boolean focus){Map<?,?> row=(Map<?,?>)value;JLabel label=(JLabel)super.getListCellRendererComponent(list,row.get("label")+(Boolean.parseBoolean(String.valueOf(row.get("required")))?"  (required final)":""),index,selected,focus);label.setIcon(JavaCompletionIcons.forItem(Map.of("icon",field?"field":"constructor"),Math.max(16,list.getFont().getSize())));label.setBorder(BorderFactory.createEmptyBorder(6,field?24:8,6,12));return label;}};}
    private static JButton link(String text){JButton button=new JButton(text);button.setForeground(BLUE);button.setBorder(BorderFactory.createEmptyBorder(3,0,3,0));button.setContentAreaFilled(false);button.setFocusPainted(false);return button;}
    private static JButton button(String text,boolean primary){JButton button=new JButton(text){@Override protected void paintComponent(Graphics g){Graphics2D copy=(Graphics2D)g.create();copy.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);copy.setColor(primary?BLUE:Color.WHITE);copy.fillRoundRect(0,0,getWidth()-1,getHeight()-1,8,8);copy.setColor(isFocusOwner()?BLUE:primary?BLUE:LINE);copy.drawRoundRect(0,0,getWidth()-1,getHeight()-1,8,8);copy.dispose();super.paintComponent(g);}};button.setUI(new javax.swing.plaf.basic.BasicButtonUI());button.setContentAreaFilled(false);button.setFocusPainted(false);button.setForeground(primary?Color.WHITE:TEXT);button.setBorder(BorderFactory.createEmptyBorder(7,20,7,20));return button;}
    static void fit(JDialog dialog){dialog.pack();GraphicsConfiguration gc=dialog.getOwner()==null?dialog.getGraphicsConfiguration():dialog.getOwner().getGraphicsConfiguration();if(gc!=null){Rectangle area=new Rectangle(gc.getBounds());Insets insets=Toolkit.getDefaultToolkit().getScreenInsets(gc);area.x+=insets.left;area.y+=insets.top;area.width-=insets.left+insets.right;area.height-=insets.top+insets.bottom;dialog.setSize(Math.min(dialog.getWidth(),area.width),Math.min(dialog.getHeight(),area.height));dialog.setLocationRelativeTo(dialog.getOwner());dialog.setLocation(Math.max(area.x,Math.min(dialog.getX(),area.x+area.width-dialog.getWidth())),Math.max(area.y,Math.min(dialog.getY(),area.y+area.height-dialog.getHeight())));}else dialog.setLocationRelativeTo(dialog.getOwner());}
    static void font(Component component,int size){int style="customJavaGenerationTitle".equals(component.getName())?Font.BOLD:Font.PLAIN;if(component.getFont()!=null&&(component.getFont().getSize()!=size||component.getFont().getStyle()!=style))component.setFont(component.getFont().deriveFont(style,(float)size));if(component instanceof Container)for(Component child:((Container)component).getComponents())font(child,size);}
}
