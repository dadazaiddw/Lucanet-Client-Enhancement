package cn.lucanet.enhancement.exit;

import com.sun.source.tree.*;
import com.sun.source.util.*;
import java.util.*;
import java.util.stream.Collectors;
import javax.lang.model.element.*;
import javax.lang.model.type.*;
import javax.lang.model.util.*;

/** Field-based generation using the attributed, unsaved compilation unit. */
final class JavaMemberGeneration {
    private final CompilationUnitTree unit;
    private final Trees trees;
    private final Elements elements;
    private final Types types;
    private final String source;
    private final TreePath path;
    private final TypeElement owner;
    private final List<Field> fields=new ArrayList<>();
    private final List<ExecutableElement> ownMethods;
    private final List<ExecutableElement> inherited;
    private int at=-1;
    private String indent="    ",outer="";

    JavaMemberGeneration(CompilationUnitTree unit,Trees trees,Elements elements,Types types,String source,int caret){
        this.unit=unit;this.trees=trees;this.elements=elements;this.types=types;this.source=source;
        path=new TreePathScanner<TreePath,Void>(){
            @Override public TreePath visitClass(ClassTree node,Void unused){
                long start=trees.getSourcePositions().getStartPosition(unit,node),end=trees.getSourcePositions().getEndPosition(unit,node);
                if(caret<start||caret>=end)return null;
                TreePath inner=super.visitClass(node,unused);return inner!=null?inner:getCurrentPath();
            }
            @Override public TreePath reduce(TreePath a,TreePath b){return a!=null?a:b;}
        }.scan(unit,null);
        Element element=path==null?null:trees.getElement(path);owner=element instanceof TypeElement?(TypeElement)element:null;
        ownMethods=owner==null?List.of():ElementFilter.methodsIn(owner.getEnclosedElements());
        inherited=owner==null?List.of():ElementFilter.methodsIn(elements.getAllMembers(owner));
        if(owner==null)return;
        ClassTree declaration=(ClassTree)path.getLeaf();
        for(Tree member:declaration.getMembers())if(member instanceof VariableTree){
            VariableTree tree=(VariableTree)member;Element e=trees.getElement(new TreePath(path,tree));
            if(e instanceof VariableElement&&!e.getModifiers().contains(Modifier.STATIC))fields.add(new Field((VariableElement)e,tree.getInitializer()!=null));
        }
        // A blank final assigned by an instance initializer must not be assigned a second time.
        for(Tree member:declaration.getMembers())if(member instanceof BlockTree&&!((BlockTree)member).isStatic())
            new TreePathScanner<Void,Void>(){
                @Override public Void visitAssignment(AssignmentTree tree,Void unused){
                    Element assigned=trees.getElement(new TreePath(getCurrentPath(),tree.getVariable()));
                    for(Field field:fields)if(field.element.equals(assigned))field.initialized=true;
                    return super.visitAssignment(tree,unused);
                }
                @Override public Void visitClass(ClassTree tree,Void unused){return null;}
            }.scan(new TreePath(path,member),null);
        int closing=(int)trees.getSourcePositions().getEndPosition(unit,declaration)-1;
        if(closing<0||closing>=source.length()||source.charAt(closing)!='}')return;
        at=insertion(unit,trees,source,declaration,caret);
        int begin=(int)trees.getSourcePositions().getStartPosition(unit,declaration),start=source.lastIndexOf('\n',begin-1)+1,end=start;
        while(end<begin&&(source.charAt(end)==' '||source.charAt(end)=='\t'))end++;
        outer=source.substring(start,end);indent=outer+"    ";
    }

    List<Map<String,String>> apply(String kind,Map<String,Object> input){
        if(owner==null||owner.getSimpleName().length()==0||at<0)return info("请将光标放在完整的具名类内部");
        if(owner.getKind()!=ElementKind.CLASS)return info("字段方法生成适用于普通类、抽象类和异常类；接口、枚举及 Record 可使用 Ctrl+O");
        if(!Set.of("constructor","getter","setter","accessors","equalsHashCode","toString").contains(kind))return info("不支持的生成类型");
        List<ExecutableElement> constructors=constructors();
        if(kind.equals("constructor")&&constructors.isEmpty())return info("父类没有可调用的构造方法，或需要外部类实例");
        if(!input.containsKey("selectedFields")){
            List<Map<String,String>> rows=new ArrayList<>();
            rows.add(new HashMap<>(Map.of("kind","generation","label",title(kind),"name",kind,"owner",owner.getSimpleName().toString(),"superAvailable",String.valueOf(hasSuperValueMethods()))));
            for(Field field:fields)if(eligible(field,kind)){
                Map<String,String> row=new HashMap<>(Map.of("kind","field","name",field.name(),"label",field.name()+" : "+field.type().toString().replace("java.lang.",""),"required",String.valueOf(kind.equals("constructor")&&field.required()),"primitive",String.valueOf(field.type().getKind().isPrimitive()),"getter",String.valueOf(readableGetter(field)!=null)));
                rows.add(row);
            }
            if(kind.equals("constructor"))for(int i=0;i<constructors.size();i++)rows.add(Map.of("kind","superConstructor","name",String.valueOf(i),"label",constructors.get(i).toString()));
            if((kind.equals("getter")||kind.equals("setter")||kind.equals("accessors"))&&rows.size()==1)return info("没有可生成的字段方法；已存在或不可重写的方法会自动跳过");
            return rows;
        }
        Object selection=input.get("selectedFields");if(!(selection instanceof List))return info("字段选择已失效，请重新打开生成菜单");
        Set<?> selected=new HashSet<>((List<?>)selection);
        List<Field> chosen=fields.stream().filter(field->eligible(field,kind)&&(selected.contains(field.name())||kind.equals("constructor")&&field.required())).collect(Collectors.toList());
        if(chosen.stream().anyMatch(field->containsError(field.type())))return info("请先修复选中字段的类型错误，再生成方法");
        StringBuilder body=new StringBuilder();
        boolean includeSuper=Boolean.TRUE.equals(input.get("includeSuper"))&&hasSuperValueMethods();
        switch(kind){
            case "constructor":
                int index;try{index=Integer.parseInt(String.valueOf(input.getOrDefault("superConstructor","0")));}catch(NumberFormatException invalid){return info("父类构造方法选择无效");}
                if(index<0||index>=constructors.size())return info("父类构造方法已变化，请重新选择");
                String constructor=constructor(chosen,constructors.get(index));
                if(constructor==null)return info("相同参数类型的构造方法已存在，或父类签名尚无法解析");
                body.append(constructor);break;
            case "getter":case "setter":case "accessors":
                Set<String> emitted=new HashSet<>();
                for(Field field:chosen){
                    if(!kind.equals("setter")&&getterAvailable(field)&&emitted.add(getter(field)+"()"))
                        body.append(method("public "+field.type()+" "+getter(field)+"()",List.of("return "+field.name()+";"),false));
                    if(!kind.equals("getter")&&setterAvailable(field)&&emitted.add(setter(field)+"("+types.erasure(field.type())+")"))
                        body.append(method("public void "+setter(field)+"("+field.type()+" "+field.name()+")",List.of("this."+field.name()+" = "+field.name()+";"),false));
                }break;
            case "equalsHashCode":
                TypeMirror object=elements.getTypeElement("java.lang.Object").asType();
                if(!canGenerate("equals",List.of(object),types.getPrimitiveType(TypeKind.BOOLEAN))||!canGenerate("hashCode",List.of(),types.getPrimitiveType(TypeKind.INT)))
                    return info("equals 或 hashCode 已存在，或被父类限制；为保持二者一致，本次未插入代码");
                Set<?> hashSelection=input.get("hashFields") instanceof List?new HashSet<>((List<?>)input.get("hashFields")):selected;
                List<Field> hashFields=chosen.stream().filter(field->hashSelection.contains(field.name())).collect(Collectors.toList());
                body.append(equalsMethod(chosen,includeSuper,input)).append(hashMethod(hashFields,includeSuper,input));break;
            case "toString":
                if(!canGenerate("toString",List.of(),elements.getTypeElement("java.lang.String").asType()))return info("toString 已存在或不能重写");
                body.append(toStringMethod(chosen,includeSuper,input));break;
            default:break;
        }
        if(body.length()==0)return info("未选择可生成的字段，或对应方法已存在");
        if(at<source.length()&&!Character.isWhitespace(source.charAt(at)))body.append(source.charAt(at)=='}'?outer:indent);
        Map<String,String> row=new HashMap<>(Map.of("kind","generated","label",title(kind),"insert",body.toString(),"replaceStart",String.valueOf(at),"replaceEnd",String.valueOf(at),"caret",String.valueOf(body.length())));
        new JavaGeneratedNames(unit,trees,elements,path).shorten(row);
        if(source.contains("\r\n")){row.put("insert",row.get("insert").replace("\n","\r\n"));row.put("caret",String.valueOf(row.get("insert").length()));}
        return List.of(row);
    }

    static String title(String kind){switch(kind){case "constructor":return "Constructor";case "getter":return "Getter";case "setter":return "Setter";case "accessors":return "Getter and Setter";case "equalsHashCode":return "equals() and hashCode()";case "toString":return "toString()";default:return kind;}}
    // A method cannot be declared inside another method/field initializer. Snap to that
    // member's end while retaining the caret's order among the surrounding members.
    static int insertion(CompilationUnitTree unit,Trees trees,String source,ClassTree declaration,int caret){
        SourcePositions positions=trees.getSourcePositions();
        int closing=(int)positions.getEndPosition(unit,declaration)-1;
        if(closing<0||closing>=source.length()||source.charAt(closing)!='}')return -1;
        String mask=JavaIntelligence.mask(source);int depth=1,opening=closing;
        while(--opening>=0){char c=mask.charAt(opening);if(c=='}')depth++;else if(c=='{'&&--depth==0)break;}
        if(opening<0)return -1;
        int original=Math.max(opening+1,Math.min(caret,closing)),offset=original;
        for(Tree member:declaration.getMembers()){
            long begin=positions.getStartPosition(unit,member),end=positions.getEndPosition(unit,member);
            if(begin>=0&&end>=begin&&original>begin&&original<end)offset=Math.max(offset,(int)end);
        }
        // Move outside a comment without detaching Javadoc from the following member.
        int[] comment=commentAt(source,offset);
        if(comment!=null){
            int line=source.lastIndexOf('\n',comment[0]-1)+1;
            offset=source.substring(line,comment[0]).isBlank()?comment[0]:comment[1];
        }
        int line=source.lastIndexOf('\n',offset-1)+1;
        if(source.substring(line,offset).isBlank())offset=line;
        else {
            // Keep same-line comments with the preceding member; leave later members in place.
            int next=offset;while(next<closing&&(source.charAt(next)==' '||source.charAt(next)=='\t'))next++;
            if(source.startsWith("//",next)||source.startsWith("/*",next)){
                int[] trailing=commentAt(source,next+2);if(trailing!=null)offset=trailing[1];
            }
            int newline=source.indexOf('\n',offset);
            if(newline>=0&&newline<closing&&source.substring(offset,newline).isBlank())offset=newline+1;
        }
        if(offset>=source.lastIndexOf('\n',closing-1)+1&&offset>0){
            int previous=source.lastIndexOf('\n',offset-2)+1;String text=source.substring(previous,offset).strip();
            if(text.startsWith("// ===")&&text.contains("End of Class"))offset=previous;
        }
        return offset;
    }
    private static int[] commentAt(String source,int caret){
        for(int i=0;i<source.length()&&i<=caret;){
            if(source.startsWith("//",i)||source.startsWith("/*",i)){
                int end=source.startsWith("//",i)?source.indexOf('\n',i+2):source.indexOf("*/",i+2);
                end=end<0?source.length():end+(source.startsWith("/*",i)?2:0);
                if(caret>=i&&caret<end)return new int[]{i,end};i=end;
            }else if(source.charAt(i)=='"'||source.charAt(i)=='\''){
                boolean block=source.startsWith("\"\"\"",i);char quote=source.charAt(i);i+=block?3:1;
                while(i<source.length()){
                    if(source.charAt(i)=='\\'){i=Math.min(source.length(),i+2);continue;}
                    if(block?source.startsWith("\"\"\"",i):source.charAt(i)==quote){i+=block?3:1;break;}i++;
                }
            }else i++;
        }
        return null;
    }
    private static List<Map<String,String>> info(String message){return List.of(Map.of("kind","info","label",message));}
    private boolean eligible(Field field,String kind){
        switch(kind){case "constructor":return !field.isFinal()||!field.initialized;case "getter":return getterAvailable(field);case "setter":return setterAvailable(field);case "accessors":return getterAvailable(field)||setterAvailable(field);default:return true;}
    }
    private boolean getterAvailable(Field f){return canGenerate(getter(f),List.of(),f.type());}
    private boolean setterAvailable(Field f){return !f.isFinal()&&canGenerate(setter(f),List.of(f.type()),types.getNoType(TypeKind.VOID));}
    private String property(Field f){String name=f.name();return f.type().getKind()==TypeKind.BOOLEAN&&name.length()>2&&name.startsWith("is")&&Character.isUpperCase(name.charAt(2))?name.substring(2):capitalize(name);}
    private String getter(Field f){return (f.type().getKind()==TypeKind.BOOLEAN?"is":"get")+property(f);}
    private String setter(Field f){return "set"+property(f);}
    private static String capitalize(String name){return name.length()>1&&Character.isUpperCase(name.charAt(1))?name:Character.toUpperCase(name.charAt(0))+name.substring(1);}
    private boolean canGenerate(String name,List<? extends TypeMirror> params,TypeMirror result){
        for(ExecutableElement method:inherited)if(method.getSimpleName().contentEquals(name)){
            ExecutableType signature=(ExecutableType)types.asMemberOf((DeclaredType)owner.asType(),method);
            if(!sameParams(signature.getParameterTypes(),params))continue;
            if(method.getEnclosingElement().equals(owner)||method.getModifiers().contains(Modifier.FINAL)||method.getModifiers().contains(Modifier.STATIC))return false;
            for(int i=0;i<params.size();i++)if(!types.isSameType(signature.getParameterTypes().get(i),params.get(i)))return false;
            if(!types.isSameType(result,signature.getReturnType())&&!types.isSubtype(result,signature.getReturnType()))return false;
        }
        // In malformed source javac may omit an erroneous declaration from getAllMembers.
        for(ExecutableElement method:ownMethods)if(method.getSimpleName().contentEquals(name)&&sameParams(((ExecutableType)method.asType()).getParameterTypes(),params))return false;
        return true;
    }
    private boolean sameParams(List<? extends TypeMirror> a,List<? extends TypeMirror> b){if(a.size()!=b.size())return false;for(int i=0;i<a.size();i++)if(!types.isSameType(types.erasure(a.get(i)),types.erasure(b.get(i))))return false;return true;}
    private List<ExecutableElement> constructors(){
        TypeMirror parent=owner.getSuperclass();if(parent.getKind()!=TypeKind.DECLARED)return List.of();
        TypeElement type=(TypeElement)((DeclaredType)parent).asElement();
        if(type.getNestingKind()==NestingKind.MEMBER&&!type.getModifiers().contains(Modifier.STATIC))return List.of();
        List<ExecutableElement> result=new ArrayList<>();
        for(ExecutableElement ctor:ElementFilter.constructorsIn(type.getEnclosedElements())){
            Set<Modifier> mods=ctor.getModifiers();
            if(!mods.contains(Modifier.PRIVATE)&&(mods.contains(Modifier.PUBLIC)||mods.contains(Modifier.PROTECTED)||elements.getPackageOf(type).equals(elements.getPackageOf(owner))))result.add(ctor);
        }
        result.sort(Comparator.comparingInt(ctor->ctor.getParameters().size()));return result;
    }
    private String constructor(List<Field> chosen,ExecutableElement parent){
        ExecutableType signature=(ExecutableType)types.asMemberOf((DeclaredType)owner.getSuperclass(),parent);
        List<TypeMirror> parameters=new ArrayList<>(signature.getParameterTypes());for(Field f:chosen)parameters.add(f.type());
        if(parameters.stream().anyMatch(JavaMemberGeneration::containsError))return null;
        for(ExecutableElement existing:ElementFilter.constructorsIn(owner.getEnclosedElements())){
            Tree tree=trees.getTree(existing);long end=tree==null?-1:trees.getSourcePositions().getEndPosition(unit,tree);
            if(end>=0&&sameParams(((ExecutableType)existing.asType()).getParameterTypes(),parameters))return null;
        }
        List<String> declarations=new ArrayList<>(),arguments=new ArrayList<>(),lines=new ArrayList<>();
        Set<String> used=chosen.stream().map(Field::name).collect(Collectors.toSet());
        for(int i=0;i<signature.getParameterTypes().size();i++){
            String name=parent.getParameters().get(i).getSimpleName().toString();while(!used.add(name))name+="_";
            declarations.add(signature.getParameterTypes().get(i)+" "+name);arguments.add(name);
        }
        for(Field f:chosen)declarations.add(f.type()+" "+f.name());
        if(!arguments.isEmpty())lines.add("super("+String.join(", ",arguments)+");");
        for(Field f:chosen)lines.add("this."+f.name()+" = "+f.name()+";");
        StringJoiner vars=new StringJoiner(", ","<","> ").setEmptyValue("");
        for(TypeVariable variable:signature.getTypeVariables()){
            // A constructor type variable must not change the meaning of a class type variable.
            if(owner.getTypeParameters().stream().anyMatch(p->p.getSimpleName().contentEquals(variable.toString())))return null;
            String bound=variable.getUpperBound().toString();vars.add(variable+(bound.equals("java.lang.Object")?"":" extends "+bound));
        }
        String thrown=signature.getThrownTypes().isEmpty()?"":" throws "+signature.getThrownTypes().stream().map(Object::toString).collect(Collectors.joining(", "));
        return method("public "+vars+owner.getSimpleName()+"("+String.join(", ",declarations)+")"+thrown,lines,false);
    }
    private boolean hasSuperValueMethods(){
        TypeMirror parent=owner.getSuperclass();if(parent.getKind()!=TypeKind.DECLARED||types.erasure(parent).toString().equals("java.lang.Object"))return false;
        Set<String> concrete=new HashSet<>();
        for(ExecutableElement method:ElementFilter.methodsIn(elements.getAllMembers((TypeElement)((DeclaredType)parent).asElement())))
            if(Set.of("equals","hashCode","toString").contains(method.getSimpleName().toString())&&!method.getModifiers().contains(Modifier.ABSTRACT))concrete.add(method.getSimpleName().toString());
        return concrete.size()==3;
    }
    private String readableGetter(Field field){
        for(ExecutableElement method:inherited)if(method.getSimpleName().contentEquals(getter(field))&&!method.getModifiers().contains(Modifier.STATIC)&&method.getParameters().isEmpty()){
            ExecutableType signature=(ExecutableType)types.asMemberOf((DeclaredType)owner.asType(),method);
            if(types.isSameType(signature.getReturnType(),field.type())&&signature.getThrownTypes().isEmpty()
                &&(!method.getModifiers().contains(Modifier.PRIVATE)||method.getEnclosingElement().equals(owner)))return getter(field)+"()";
        }
        return null;
    }
    private String accessor(Field field,Map<String,Object> input){String getter=Boolean.TRUE.equals(input.get("useGetters"))?readableGetter(field):null;return getter==null?field.name():getter;}
    private String unique(String base,Set<String> used){String name=base;for(int i=1;javax.lang.model.SourceVersion.isKeyword(name)||!used.add(name);i++)name=base+i;return name;}
    private String equalsMethod(List<Field> chosen,boolean includeSuper,Map<String,Object> input){
        String cast=owner.getSimpleName()+wildcards(owner.getTypeParameters().size());
        Set<String> names=fields.stream().map(Field::name).collect(Collectors.toSet());
        String argument=unique("o",names),className=owner.getSimpleName().toString(),instance=unique(Character.toLowerCase(className.charAt(0))+className.substring(1),names);
        List<String> lines=new ArrayList<>();
        lines.add(Boolean.TRUE.equals(input.get("useInstanceof"))?"if (!("+argument+" instanceof "+owner.getSimpleName()+")) return false;":"if ("+argument+" == null || getClass() != "+argument+".getClass()) return false;");
        if(includeSuper)lines.add("if (!super.equals("+argument+")) return false;");
        if(!chosen.isEmpty())lines.add(cast+" "+instance+" = ("+cast+") "+argument+";");
        List<String> checks=new ArrayList<>();
        List<Field> ordered=new ArrayList<>(chosen);ordered.sort(Comparator.comparingInt(field->field.type().getKind().isPrimitive()?0:1));
        Set<?> nonNull=input.get("nonNullFields") instanceof List?new HashSet<>((List<?>)input.get("nonNullFields")):Set.of();
        for(Field f:ordered){String left=accessor(f,input),right=instance+"."+left;
            switch(f.type().getKind()){
                case FLOAT:checks.add("java.lang.Float.compare("+left+", "+right+") == 0");break;
                case DOUBLE:checks.add("java.lang.Double.compare("+left+", "+right+") == 0");break;
                case ARRAY:checks.add("java.util.Objects.deepEquals("+left+", "+right+")");break;
                default:
                    boolean enumType=f.type().getKind()==TypeKind.DECLARED&&((DeclaredType)f.type()).asElement().getKind()==ElementKind.ENUM;
                    checks.add(f.type().getKind().isPrimitive()||enumType?left+" == "+right:nonNull.contains(f.name())?left+".equals("+right+")":"java.util.Objects.equals("+left+", "+right+")");break;
            }
        }
        lines.add("return "+(checks.isEmpty()?"true":String.join(" && ",checks))+";");
        return method("public boolean equals(java.lang.Object "+argument+")",lines,true);
    }
    private String hashMethod(List<Field> chosen,boolean includeSuper,Map<String,Object> input){
        List<String> values=new ArrayList<>();if(includeSuper)values.add("super.hashCode()");
        for(Field f:chosen){String value=accessor(f,input);if(f.type().getKind()==TypeKind.ARRAY)value="java.util.Arrays."+(deep(f)?"deepHashCode":"hashCode")+"("+value+")";values.add(value);}
        String expression="java.util.Objects.hash("+String.join(", ",values)+")";
        if(!includeSuper&&chosen.size()==1)expression=chosen.get(0).type().getKind()==TypeKind.ARRAY?values.get(0):"java.util.Objects.hashCode("+values.get(0)+")";
        return method("public int hashCode()",List.of("return "+expression+";"),true);
    }
    private String toStringMethod(List<Field> chosen,boolean includeSuper,Map<String,Object> input){
        boolean annotation=!Boolean.FALSE.equals(input.get("insertOverride"));
        if(chosen.isEmpty())return method("public java.lang.String toString()",List.of("return \""+owner.getSimpleName()+"{}"+(includeSuper?" \" + super.toString();":"\";")),annotation);
        StringBuilder expression=new StringBuilder("return \"").append(owner.getSimpleName()).append("{\" +");int index=0;
        for(Field f:chosen){String value=accessor(f,input);boolean quoted=f.type().toString().equals("java.lang.String");
            if(f.type().getKind()==TypeKind.ARRAY)value="java.util.Arrays."+(deep(f)?"deepToString":"toString")+"("+value+")";
            expression.append("\n").append(indent).append("        \"").append(index++==0?"":", ").append(f.name()).append(quoted?"='\" + ":"=\" + ").append(value);
            if(quoted)expression.append(" + '\\''");expression.append(" +");
        }
        expression.append("\n").append(indent).append("        ").append(includeSuper?"\"} \" + super.toString();":"'}';");
        return method("public java.lang.String toString()",List.of(expression.toString()),annotation);
    }
    private String method(String header,List<String> lines,boolean override){
        StringBuilder out=new StringBuilder("\n");if(override)out.append(indent).append("@java.lang.Override\n");
        out.append(indent).append(header).append(" {\n");for(String line:lines)out.append(indent).append("    ").append(line).append('\n');
        return out.append(indent).append("}\n").toString();
    }
    private static String wildcards(int size){return size==0?"":"<"+String.join(", ",Collections.nCopies(size,"?"))+">";}
    private static boolean deep(Field f){return !((ArrayType)f.type()).getComponentType().getKind().isPrimitive();}
    private static boolean containsError(TypeMirror type){
        if(type.getKind()==TypeKind.ERROR)return true;
        if(type.getKind()==TypeKind.ARRAY)return containsError(((ArrayType)type).getComponentType());
        if(type.getKind()==TypeKind.DECLARED)return ((DeclaredType)type).getTypeArguments().stream().anyMatch(JavaMemberGeneration::containsError);
        return false;
    }
    private static final class Field{
        final VariableElement element;boolean initialized;
        Field(VariableElement element,boolean initialized){this.element=element;this.initialized=initialized;}
        String name(){return element.getSimpleName().toString();}TypeMirror type(){return element.asType();}
        boolean isFinal(){return element.getModifiers().contains(Modifier.FINAL);}boolean required(){return isFinal()&&!initialized;}
    }
}
