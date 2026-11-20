package cn.lucanet.enhancement.exit;

import com.sun.source.tree.*;
import com.sun.source.util.*;
import java.util.*;
import java.util.regex.*;
import javax.lang.model.element.*;
import javax.lang.model.util.Elements;

/** Shortens only resolved type tokens in generated code, preserving strings and existing bindings. */
final class JavaGeneratedNames {
    private static final Pattern QUALIFIED=Pattern.compile("[\\p{javaJavaIdentifierStart}][\\p{javaJavaIdentifierPart}]*(?:\\.[\\p{javaJavaIdentifierStart}][\\p{javaJavaIdentifierPart}]*)+");
    private final Elements elements;
    private final String pkg;
    private final Map<String,String> bindings=new HashMap<>();
    private final Set<String> imports=new HashSet<>(),wildcards=new HashSet<>();
    private final Set<String> blocked=new HashSet<>();
    private final Set<String> visibleTypes=new HashSet<>();

    JavaGeneratedNames(CompilationUnitTree unit,Trees trees,Elements elements,TreePath owner){
        this.elements=elements;pkg=unit.getPackageName()==null?"":unit.getPackageName().toString();
        for(ImportTree tree:unit.getImports()){
            String name=tree.getQualifiedIdentifier().toString();
            if(name.endsWith(".*")) { wildcards.add(name.substring(0,name.length()-2)); continue; }
            if(!tree.isStatic()||elements.getTypeElement(name)!=null){imports.add(name);bind(simple(name),name);}
            else blocked.add(simple(name));
        }
        for(Tree declaration:unit.getTypeDecls())if(declaration instanceof ClassTree){
            Element e=trees.getElement(TreePath.getPath(unit,declaration));
            if(e instanceof TypeElement){bind(e.getSimpleName().toString(),((TypeElement)e).getQualifiedName().toString());visibleTypes.add(((TypeElement)e).getQualifiedName().toString());}
        }
        // Adding an import must not silently change an existing unqualified type elsewhere in this unit.
        new TreePathScanner<Void,Void>(){
            @Override public Void visitIdentifier(IdentifierTree node,Void unused){
                Element resolved=trees.getElement(getCurrentPath());
                if(resolved instanceof TypeElement&&resolved.asType().getKind()!=javax.lang.model.type.TypeKind.ERROR)bind(node.getName().toString(),((TypeElement)resolved).getQualifiedName().toString());
                return super.visitIdentifier(node,unused);
            }
        }.scan(unit,null);
        for(TreePath cursor=owner;cursor!=null;cursor=cursor.getParentPath()){
            Element e=trees.getElement(cursor);
            if(cursor.getLeaf() instanceof MethodTree&&e instanceof ExecutableElement){for(TypeParameterElement p:((ExecutableElement)e).getTypeParameters())blocked.add(p.getSimpleName().toString());}
            if(!(cursor.getLeaf() instanceof ClassTree)||!(e instanceof TypeElement))continue;
            TypeElement type=(TypeElement)e;
            bind(type.getSimpleName().toString(),type.getQualifiedName().toString());
            visibleTypes.add(type.getQualifiedName().toString());
            for(TypeParameterElement p:type.getTypeParameters())blocked.add(p.getSimpleName().toString());
            for(Element member:elements.getAllMembers(type)){
                if(member instanceof TypeElement){bind(member.getSimpleName().toString(),((TypeElement)member).getQualifiedName().toString());visibleTypes.add(((TypeElement)member).getQualifiedName().toString());}
                // Static helper calls such as Objects.equals must not resolve to a field.
                if(member instanceof VariableElement)blocked.add(member.getSimpleName().toString());
            }
        }
        if(owner!=null)try{
            for(Scope scope=trees.getScope(owner);scope!=null;scope=scope.getEnclosingScope())for(Element e:scope.getLocalElements()){
                if(e instanceof TypeParameterElement)blocked.add(e.getSimpleName().toString());
                if(e instanceof TypeElement&&((TypeElement)e).getNestingKind()==NestingKind.LOCAL&&e.asType().getKind()!=javax.lang.model.type.TypeKind.ERROR){bind(e.getSimpleName().toString(),((TypeElement)e).getQualifiedName().toString());visibleTypes.add(((TypeElement)e).getQualifiedName().toString());}
            }
        }catch(RuntimeException incomplete){/* The declaration scan still gives conservative bindings. */}
    }
    private void bind(String simple,String name){String old=bindings.putIfAbsent(simple,name);if(old!=null&&!old.equals(name))blocked.add(simple);}
    JavaGeneratedNames(JavaGeneratedNames original){elements=original.elements;pkg=original.pkg;bindings.putAll(original.bindings);imports.addAll(original.imports);wildcards.addAll(original.wildcards);blocked.addAll(original.blocked);visibleTypes.addAll(original.visibleTypes);}
    private static String simple(String name){return name.substring(name.lastIndexOf('.')+1);}

    static TreePath at(CompilationUnitTree unit,Trees trees,int offset){
        return new TreePathScanner<TreePath,Void>(){
            @Override public TreePath scan(Tree tree,Void unused){
                if(tree==null)return null;
                long start=trees.getSourcePositions().getStartPosition(unit,tree),end=trees.getSourcePositions().getEndPosition(unit,tree);
                if(start>offset||end>=0&&offset>end)return null;
                TreePath current=getCurrentPath()==null?new TreePath(unit):new TreePath(getCurrentPath(),tree);
                TreePath inner=super.scan(tree,unused);return inner!=null?inner:current;
            }
            @Override public TreePath reduce(TreePath a,TreePath b){return a!=null?a:b;}
        }.scan(unit,null);
    }

    void shorten(Map<String,String> row){
        String code=row.get("insert");if(code==null)return;
        // Method type variables and generated parameter names can shadow helper/type names.
        Set<String> local=new HashSet<>();
        Matcher declarations=Pattern.compile("(?:<|,)\\s*([\\p{javaJavaIdentifierPart}]+)\\s*(?=extends\\b|[,>])").matcher(JavaIntelligence.mask(code));
        while(declarations.find())local.add(declarations.group(1));
        Set<String> needed=new TreeSet<>();
        String lexical=JavaIntelligence.mask(code);Matcher match=QUALIFIED.matcher(lexical);StringBuilder out=new StringBuilder();int last=0;
        while(match.find()){
            String candidate=match.group(),tail="";TypeElement type=elements.getTypeElement(candidate);
            while(type==null&&candidate.indexOf('.')>=0){int dot=candidate.lastIndexOf('.');tail=candidate.substring(dot)+tail;candidate=candidate.substring(0,dot);type=elements.getTypeElement(candidate);}
            if(type==null||candidate.indexOf('.')<0)continue;
            String name=type.getSimpleName().toString(),qualified=type.getQualifiedName().toString();
            if(blocked.contains(name)||local.contains(name))continue;
            String bound=bindings.get(name);if(bound!=null&&!bound.equals(qualified))continue;
            TypeElement samePackage=elements.getTypeElement((pkg.isEmpty()?"":pkg+".")+name);
            if(bound==null&&samePackage!=null&&!samePackage.getQualifiedName().contentEquals(qualified))continue;
            boolean topLevel=type.getEnclosingElement() instanceof PackageElement;
            String owner=topLevel?elements.getPackageOf(type).getQualifiedName().toString():"";
            boolean implicit=topLevel&&(owner.equals(pkg)||owner.equals("java.lang"));
            if(implicit&&owner.equals("java.lang")&&!imports.contains(qualified)){
                boolean conflict=false;
                for(String wildcard:wildcards){TypeElement other=elements.getTypeElement(wildcard+"."+name);if(other!=null&&!other.getQualifiedName().contentEquals(qualified)){conflict=true;break;}}
                if(conflict)continue;
            }
            bindings.put(name,qualified);
            if(!implicit&&!imports.contains(qualified)&&!visibleTypes.contains(qualified))needed.add(qualified);
            out.append(code,last,match.start()).append(name).append(tail);last=match.end();
        }
        out.append(code,last,code.length());row.put("insert",out.toString());row.put("caret",String.valueOf(out.length()));
        if(!needed.isEmpty())row.put("imports",String.join("\n",needed));
    }
}
