package cn.lucanet.enhancement.exit;

import java.awt.*;
import java.awt.geom.*;
import java.io.*;
import java.util.*;
import java.util.List;
import java.util.regex.*;
import javax.swing.Icon;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/** Renders the bundled, unmodified IntelliJ New UI vectors without depending on the host's SVG library.
 * Only the path/circle subset used by these pinned resources is supported; no external SVG is accepted. */
final class JavaCompletionIcons {
    private static final Set<String> NAMES=Set.of("class","classAbstract","interface","enum","annotation","record",
            "exception","abstractException","method","methodAbstract","field","variable","parameter","template","staticMark","finalMark");
    private static final Map<String,List<Layer>> VECTORS=new HashMap<>();
    private static final Map<String,Icon> ICONS=new HashMap<>();
    private JavaCompletionIcons(){}
    static synchronized Icon forItem(Map<String,String> item,int size) {
        String kind=item.getOrDefault("kind","");
        String name=item.getOrDefault("icon",kind.equals("postfix")?"template":kind.equals("constructor")?"method":kind);
        if(!NAMES.contains(name))return null; // IDEA keywords and informational rows have no symbol icon.
        boolean stat=Boolean.parseBoolean(item.get("static")),fin=Boolean.parseBoolean(item.get("final"));
        String key=name+":"+stat+":"+fin+":"+size;
        return ICONS.computeIfAbsent(key,k->{
            List<Layer> layers=new ArrayList<>(vectors(name));
            if(stat)layers.addAll(vectors("staticMark"));if(fin)layers.addAll(vectors("finalMark"));
            return new Icon(){
                public int getIconWidth(){return size;}public int getIconHeight(){return size;}
                public void paintIcon(Component component,Graphics graphics,int x,int y){
                    Graphics2D g=(Graphics2D)graphics.create();g.translate(x,y);g.scale(size/16.0,size/16.0);
                    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
                    g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL,RenderingHints.VALUE_STROKE_PURE);
                    for(Layer layer:layers)layer.paint(g);g.dispose();
                }
            };
        });
    }
    private static List<Layer> vectors(String name) {
        return VECTORS.computeIfAbsent(name,k->{
            try(InputStream input=JavaCompletionIcons.class.getResourceAsStream("idea-icons/"+name+".svg")) {
                if(input==null)throw new IOException("Missing IDEA icon: "+name);
                DocumentBuilderFactory factory=DocumentBuilderFactory.newDefaultInstance();
                factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
                factory.setAttribute("http://javax.xml.XMLConstants/property/accessExternalDTD","");
                factory.setAttribute("http://javax.xml.XMLConstants/property/accessExternalSchema","");
                Element root=factory.newDocumentBuilder().parse(input).getDocumentElement();List<Layer> layers=new ArrayList<>();
                for(Node node=root.getFirstChild();node!=null;node=node.getNextSibling())if(node instanceof Element) {
                    Element e=(Element)node;Shape shape;
                    if(e.getTagName().equals("circle")){double r=number(e,"r",0);shape=new Ellipse2D.Double(number(e,"cx",0)-r,number(e,"cy",0)-r,r*2,r*2);}
                    else if(e.getTagName().equals("path"))shape=path(e.getAttribute("d"),e.getAttribute("fill-rule").equals("evenodd"));
                    else throw new IOException("Unsupported bundled SVG element: "+e.getTagName());
                    Color fill=color(e.hasAttribute("fill")?e.getAttribute("fill"):root.getAttribute("fill"));
                    Color stroke=color(e.getAttribute("stroke"));
                    BasicStroke pen=new BasicStroke((float)number(e,"stroke-width",1),e.getAttribute("stroke-linecap").equals("round")?BasicStroke.CAP_ROUND:BasicStroke.CAP_BUTT,
                            e.getAttribute("stroke-linejoin").equals("round")?BasicStroke.JOIN_ROUND:BasicStroke.JOIN_MITER,4);
                    layers.add(new Layer(shape,fill,stroke,pen));
                }
                return layers;
            }catch(Exception failure){throw new IllegalStateException("Cannot load bundled IDEA icon "+name,failure);}
        });
    }
    private static double number(Element e,String name,double fallback){return e.hasAttribute(name)?Double.parseDouble(e.getAttribute(name)):fallback;}
    private static Color color(String value){return value.isEmpty()||value.equals("none")?null:Color.decode(value);}
    private static Path2D path(String value,boolean evenOdd) {
        Matcher matcher=Pattern.compile("[A-Za-z]|[-+]?(?:\\d*\\.\\d+|\\d+\\.?\\d*)(?:[eE][-+]?\\d+)?").matcher(value);
        List<String> tokens=new ArrayList<>();while(matcher.find())tokens.add(matcher.group());
        Path2D.Double result=new Path2D.Double(evenOdd?Path2D.WIND_EVEN_ODD:Path2D.WIND_NON_ZERO);
        char command=0;double x=0,y=0,originX=0,originY=0;int i=0;
        while(i<tokens.size()) {
            String token=tokens.get(i);if(token.length()==1&&Character.isLetter(token.charAt(0))){command=token.charAt(0);i++;}
            if(command=='Z'){result.closePath();x=originX;y=originY;command=0;continue;}
            int count=command=='C'?6:command=='M'||command=='L'?2:command=='H'||command=='V'?1:0;
            if(count==0||i+count>tokens.size())throw new IllegalArgumentException("Unsupported bundled SVG path: "+command);
            double[] p=new double[count];for(int n=0;n<count;n++)p[n]=Double.parseDouble(tokens.get(i++));
            switch(command){
                case 'M':x=originX=p[0];y=originY=p[1];result.moveTo(x,y);command='L';break;
                case 'L':x=p[0];y=p[1];result.lineTo(x,y);break;
                case 'H':x=p[0];result.lineTo(x,y);break;
                case 'V':y=p[0];result.lineTo(x,y);break;
                case 'C':result.curveTo(p[0],p[1],p[2],p[3],p[4],p[5]);x=p[4];y=p[5];break;
                default:throw new IllegalArgumentException("Unsupported bundled SVG path: "+command);
            }
        }
        return result;
    }
    private static final class Layer {
        final Shape shape;final Color fill,stroke;final BasicStroke pen;
        Layer(Shape shape,Color fill,Color stroke,BasicStroke pen){this.shape=shape;this.fill=fill;this.stroke=stroke;this.pen=pen;}
        void paint(Graphics2D g){if(fill!=null){g.setColor(fill);g.fill(shape);}if(stroke!=null){g.setColor(stroke);g.setStroke(pen);g.draw(shape);}}
    }
}
