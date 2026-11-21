package cn.lucanet.enhancement.exit;

import com.sun.source.tree.*;
import com.sun.source.util.*;
import java.net.URI;
import java.util.*;
import java.util.function.BiFunction;
import javax.lang.model.element.Modifier;
import javax.tools.*;

/** Syntax-only compilation-unit validation, loaded with the platform compiler loader. */
public final class CustomJavaSource implements BiFunction<String,Map<String,List<Map<String,String>>>,Map<String,String>> {
    static final String SCRIPT_PACKAGE="com.lucanet.common.dom.script.rt";
    static final String FORMATTED_SOURCE="formattedSource";
    static final String FORMATTED_BODY="formattedBody";
    private static final Set<Modifier> PART_VISIBILITY=Set.of(Modifier.PUBLIC,Modifier.PROTECTED,Modifier.PRIVATE,Modifier.STATIC,Modifier.FINAL);
    private static final Set<Modifier> FIELD_MODIFIERS=Set.of(Modifier.VOLATILE,Modifier.TRANSIENT);
    private static final Set<Modifier> METHOD_MODIFIERS=Set.of(Modifier.SYNCHRONIZED,Modifier.STRICTFP);

    @Override public Map<String,String> apply(String source,Map<String,List<Map<String,String>>> parts) {
        JavaCompiler compiler=ToolProvider.getSystemJavaCompiler();
        if(compiler==null)throw new IllegalStateException("当前 Lucanet JDK 未提供 Java 编译器");
        DiagnosticCollector<JavaFileObject> diagnostics=new DiagnosticCollector<>();
        JavaFileObject file=new SimpleJavaFileObject(URI.create("string:///Source.java"),JavaFileObject.Kind.SOURCE){
            @Override public CharSequence getCharContent(boolean ignored){return source;}
        };
        try(StandardJavaFileManager manager=compiler.getStandardFileManager(diagnostics,Locale.ROOT,java.nio.charset.StandardCharsets.UTF_8)) {
            JavacTask task=(JavacTask)compiler.getTask(null,manager,diagnostics,List.of("-proc:none","-Xlint:none"),null,List.of(file));
            CompilationUnitTree unit=task.parse().iterator().next();
            for(Diagnostic<?> diagnostic:diagnostics.getDiagnostics())if(diagnostic.getKind()==Diagnostic.Kind.ERROR)
                throw new IllegalArgumentException("第 "+diagnostic.getLineNumber()+" 行："+diagnostic.getMessage(Locale.SIMPLIFIED_CHINESE));
            ClassTree primary=null;int publicTypes=0;
            for(Tree declaration:unit.getTypeDecls())if(declaration instanceof ClassTree) {
                ClassTree type=(ClassTree)declaration;
                if(type.getModifiers().getFlags().contains(Modifier.PUBLIC)){primary=type;publicTypes++;}
                else if(primary==null)primary=type;
            }
            if(primary==null)throw new IllegalArgumentException("请定义一个类、接口、枚举、注解或 Record。");
            if(publicTypes>1)throw new IllegalArgumentException("一个 Class 节点只能包含一个 public 顶层类型。");
            String name=primary.getSimpleName().toString();
            String pkg=unit.getPackageName()==null?"":unit.getPackageName().toString();
            Map<String,String> result=new LinkedHashMap<>();
            result.put("name",name);result.put("package",pkg);result.put("qualified",pkg.isEmpty()?name:pkg+"."+name);
            result.put("kind",primary.getKind().name());
            StringBuilder outline=new StringBuilder();
            for(ImportTree entry:unit.getImports())outline.append("0\t").append(entry.toString().trim()).append('\n');
            for(Tree declaration:unit.getTypeDecls())if(declaration instanceof ClassTree)outline((ClassTree)declaration,0,outline);
            result.put("outline",outline.toString());
            parts.clear();
            SourcePositions positions=Trees.instance(task).getSourcePositions();
            JavaMethodFormatting formatting=new JavaMethodFormatting(unit,positions,source);
            Map<MethodTree,String> bodies=new IdentityHashMap<>();
            String formatted=formatMethods(primary,source,formatting,bodies,SCRIPT_PACKAGE.equals(pkg));
            if(!source.equals(formatted))result.put(FORMATTED_SOURCE,formatted);
            String problem=nativeParts(unit,primary,pkg,source,positions,bodies,parts);
            if(problem!=null)result.put("nativeIssue",problem);
            return result;
        }catch(java.io.IOException failure){throw new IllegalStateException("无法解析 Java 源码",failure);}
    }

    private static String formatMethods(ClassTree owner,String source,JavaMethodFormatting formatting,
            Map<MethodTree,String> bodies,boolean nativePackage) {
        boolean nativeSections=nativePackage&&CustomJavaFormatting.hasMethodSection(source);
        StringBuilder result=new StringBuilder(source.length());
        int kept=0;
        for(Tree member:owner.getMembers()) {
            if(!(member instanceof MethodTree))continue;
            MethodTree method=(MethodTree)member;
            BlockTree body=method.getBody();
            if(body==null)continue;
            int start=formatting.start(body),end=formatting.end(body);
            if(start<kept||end<=start||source.charAt(start)!='{'||source.charAt(end-1)!='}')continue;
            formatting.scan(body,null);
            String formatted=nativeSections?formatting.withoutNativeGuard(method,owner.getSimpleName().toString()):null;
            if(formatted==null)formatted=formatting.body(body);
            if(formatted.equals(source.substring(start+1,end-1)))continue;
            bodies.put(method,formatted);
            result.append(source,kept,start+1).append(formatted);
            kept=end-1;
        }
        String formatted=kept==0?source:result.append(source,kept,source.length()).toString();
        return CustomJavaFormatting.withoutSectionComments(formatted);
    }

    private static String nativeParts(CompilationUnitTree unit,ClassTree type,String pkg,String source,
            SourcePositions positions,Map<MethodTree,String> bodies,Map<String,List<Map<String,String>>> parts) {
        List<String> issues=new ArrayList<>();
        if(!SCRIPT_PACKAGE.equals(pkg))issues.add("使用了非原生包名");
        if(!unit.getPackageAnnotations().isEmpty())issues.add("声明了包注解");
        if(unit.getTypeDecls().stream().filter(tree->tree instanceof ClassTree).count()!=1)issues.add("包含多个顶层类型");
        if(type.getKind()!=Tree.Kind.CLASS) {
            switch(type.getKind()) {
                case INTERFACE: issues.add("声明了接口");break;
                case ENUM: issues.add("声明了枚举");break;
                case ANNOTATION_TYPE: issues.add("声明了注解类型");break;
                default: issues.add("声明了 "+type.getKind()+" 类型");
            }
        }
        if(!type.getModifiers().getFlags().equals(Set.of(Modifier.PUBLIC)))issues.add("类型修饰符超出原生 Class 的 public 声明");
        if(!type.getModifiers().getAnnotations().isEmpty())issues.add("声明了类型注解");
        if(!type.getTypeParameters().isEmpty())issues.add("声明了类型参数");
        if(type.getExtendsClause()!=null)issues.add("使用了 extends");
        if(!type.getImplementsClause().isEmpty())issues.add("使用了 implements");
        if(!issues.isEmpty())return String.join("、",issues);

        List<Map<String,String>> imports=new ArrayList<>();
        List<Map<String,String>> fields=new ArrayList<>();
        List<Map<String,String>> methods=new ArrayList<>();
        for(ImportTree entry:unit.getImports()) {
            imports.add(Map.of("name",(entry.isStatic()?"static ":"")+entry.getQualifiedIdentifier()));
        }
        for(Tree member:type.getMembers()) {
            if(member instanceof VariableTree) {
                Map<String,String> part=fieldPart((VariableTree)member,unit,source,positions);
                if(part==null)return "字段 "+((VariableTree)member).getName()+" 的修饰符或初始值无法写入原生 Class";
                fields.add(part);
            }else if(member instanceof MethodTree) {
                MethodTree method=(MethodTree)member;
                Map<String,String> part=methodPart(method,type,unit,source,positions,bodies.get(method));
                if(part==null) {
                    if(method.getReturnType()==null)return "构造方法的声明或 this()/super() 调用无法写入原生 Class";
                    return "方法 "+method.getName()+" 的声明或方法体无法写入原生 Class";
                }
                methods.add(part);
            }else if(member instanceof ClassTree)return "包含嵌套类型 "+((ClassTree)member).getSimpleName();
            else if(member instanceof BlockTree)return "包含初始化块";
            else if(member.getKind()!=Tree.Kind.EMPTY_STATEMENT)return "包含原生 Class 无法保存的成员";
        }
        parts.put("imports",imports);
        parts.put("fields",fields);
        parts.put("methods",methods);
        return null;
    }

    private static Map<String,String> fieldPart(VariableTree field,CompilationUnitTree unit,String source,SourcePositions positions) {
        ModifiersTree modifiers=field.getModifiers();
        String prefix=declarationPrefix(modifiers,FIELD_MODIFIERS);
        if(prefix==null)return null;

        Map<String,String> part=new HashMap<>();
        part.put("name",field.getName().toString());
        part.put("visibility",visibility(modifiers.getFlags()));
        String fieldType=prefix+field.getType();
        ExpressionTree value=field.getInitializer();
        if(value!=null) {
            // The native renderer quotes values when the type is exactly String or char.
            if(fieldType.equals("String")&&value.getKind()==Tree.Kind.STRING_LITERAL)fieldType="java.lang.String";
            if(fieldType.equals("String"))return null;
            if(fieldType.equals("char")) {
                if(value.getKind()!=Tree.Kind.CHAR_LITERAL)return null;
                String literal=value.toString();
                part.put("value",literal.substring(1,literal.length()-1));
            }else part.put("value",slice(source,positions,unit,value));
        }
        part.put("type",fieldType);
        return part;
    }

    private static Map<String,String> methodPart(MethodTree method,ClassTree owner,CompilationUnitTree unit,
            String source,SourcePositions positions,String formattedBody) {
        ModifiersTree modifiers=method.getModifiers();
        String prefix=declarationPrefix(modifiers,METHOD_MODIFIERS);
        BlockTree body=method.getBody();
        if(prefix==null||body==null||method.getReceiverParameter()!=null)return null;
        boolean constructor=method.getReturnType()==null;
        // Lucanet wraps the body in its stack-level check, so a constructor invocation cannot be the first statement.
        if(constructor&&body.getStatements().stream().anyMatch(CustomJavaSource::delegatesConstructor))return null;
        String block=slice(source,positions,unit,body);
        if(!block.startsWith("{")||!block.endsWith("}"))return null;

        Map<String,String> part=new HashMap<>();
        part.put("name",constructor?owner.getSimpleName().toString():method.getName().toString());
        part.put("visibility",visibility(modifiers.getFlags()));
        if(!method.getTypeParameters().isEmpty())prefix+="<"+join(method.getTypeParameters())+"> ";
        String declaration=constructor?prefix.trim():prefix+method.getReturnType();
        if(!declaration.isEmpty())part.put("type",declaration);
        if(!method.getParameters().isEmpty())part.put("parameters",join(method.getParameters()));
        if(!method.getThrows().isEmpty())part.put("throws",join(method.getThrows()));
        part.put("body",block.substring(1,block.length()-1));
        if(formattedBody!=null)part.put(FORMATTED_BODY,formattedBody);
        return part;
    }

    private static String declarationPrefix(ModifiersTree modifiers,Set<Modifier> extra) {
        StringBuilder prefix=new StringBuilder();
        for(AnnotationTree annotation:modifiers.getAnnotations())prefix.append(annotation).append(' ');
        for(Modifier flag:modifiers.getFlags()) {
            if(PART_VISIBILITY.contains(flag))continue;
            if(!extra.contains(flag))return null;
            prefix.append(flag).append(' ');
        }
        return prefix.toString();
    }

    private static boolean delegatesConstructor(StatementTree statement) {
        if(!(statement instanceof ExpressionStatementTree))return false;
        ExpressionTree expression=((ExpressionStatementTree)statement).getExpression();
        if(!(expression instanceof MethodInvocationTree))return false;
        String target=((MethodInvocationTree)expression).getMethodSelect().toString();
        return target.equals("this")||target.equals("super")||target.endsWith(".super");
    }

    private static String visibility(Set<Modifier> flags) {
        String access=flags.contains(Modifier.PUBLIC)?"PUBLIC":flags.contains(Modifier.PROTECTED)?"PROTECTED":flags.contains(Modifier.PRIVATE)?"PRIVATE":"PACKAGE";
        return access+(flags.contains(Modifier.STATIC)?"_STATIC":"")+(flags.contains(Modifier.FINAL)?"_FINAL":"");
    }

    private static String slice(String source,SourcePositions positions,CompilationUnitTree unit,Tree tree) {
        return source.substring((int)positions.getStartPosition(unit,tree),(int)positions.getEndPosition(unit,tree));
    }
    private static void outline(ClassTree type,int depth,StringBuilder result) {
        String declaration=type.toString();int body=declaration.indexOf('{');
        row(result,depth,body<0?declaration:declaration.substring(0,body));
        for(Tree member:type.getMembers()) {
            if(member instanceof ClassTree)outline((ClassTree)member,depth+1,result);
            else if(member instanceof MethodTree) {
                MethodTree method=(MethodTree)member;
                String signature=method.getModifiers()+" "+(method.getTypeParameters().isEmpty()?"":"<"+join(method.getTypeParameters())+"> ")
                        +(method.getReturnType()==null?type.getSimpleName():method.getReturnType()+" "+method.getName())
                        +"("+join(method.getParameters())+")"+(method.getThrows().isEmpty()?"":" throws "+join(method.getThrows()));
                row(result,depth+1,signature);
            }else if(member instanceof VariableTree) {
                VariableTree variable=(VariableTree)member;row(result,depth+1,variable.getModifiers()+" "+variable.getType()+" "+variable.getName());
            }else if(member instanceof BlockTree)row(result,depth+1,((BlockTree)member).isStatic()?"static { … }":"{ … }");
        }
    }
    private static String join(List<? extends Tree> values){StringJoiner result=new StringJoiner(", ");values.forEach(value->result.add(value.toString()));return result.toString();}
    private static void row(StringBuilder result,int depth,String value){result.append(depth).append('\t').append(value.replaceAll("\\s+"," ").trim()).append('\n');}
}
