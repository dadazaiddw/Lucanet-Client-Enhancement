package cn.lucanet.enhancement.exit;

import com.sun.source.tree.*;
import com.sun.source.util.*;
import java.net.URI;
import java.util.*;
import java.util.function.Function;
import javax.lang.model.element.Modifier;
import javax.tools.*;

/** Syntax-only compilation-unit validation, loaded with the platform compiler loader. */
public final class CustomJavaSource implements Function<String,Map<String,String>> {
    @Override public Map<String,String> apply(String source) {
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
            result.put("outline",outline.toString());return result;
        }catch(java.io.IOException failure){throw new IllegalStateException("无法解析 Java 源码",failure);}
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
