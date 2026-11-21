package cn.lucanet.enhancement.exit;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import java.util.*;
import java.util.stream.Collectors;
import javax.lang.model.element.*;
import javax.lang.model.type.*;
import javax.lang.model.util.ElementFilter;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;

/** Uses attributed Java signatures for complete source documents. */
final class JavaTypeActions {
    private static final String MISSING_METHOD="compiler.err.does.not.override.abstract";
    private final CompilationUnitTree unit;
    private final Trees trees;
    private final Elements elements;
    private final Types types;
    private final String source;
    private final int caret;

    JavaTypeActions(CompilationUnitTree unit,Trees trees,Elements elements,Types types,String source,int caret){
        this.unit=unit;this.trees=trees;this.elements=elements;this.types=types;this.source=source;this.caret=caret;
    }

    static List<Map<String,String>> diagnostics(List<Diagnostic<? extends JavaFileObject>> errors,int length){
        List<Map<String,String>> rows=new ArrayList<>();
        for(Diagnostic<?> error:errors){
            int start=(int)Math.max(0,Math.min(length,error.getStartPosition()));
            int end=(int)Math.max(start,Math.min(length,error.getEndPosition()));
            rows.add(Map.of("start",String.valueOf(start),"end",String.valueOf(end),
                "line",String.valueOf(Math.max(1,error.getLineNumber())),"column",String.valueOf(Math.max(1,error.getColumnNumber())),
                "message",error.getMessage(Locale.SIMPLIFIED_CHINESE).replaceAll("\\s+"," "),"code",error.getCode()));
        }
        return rows;
    }

    List<Map<String,String>> fixes(List<Diagnostic<? extends JavaFileObject>> errors){
        List<Map<String,String>> rows=new ArrayList<>();
        TreePath path=enclosingType();
        if(path!=null){
            // A missing implementation is reported on the declaration, even with the caret in its body.
            boolean missing=errors.stream().anyMatch(error->MISSING_METHOD.equals(error.getCode())
                &&sameType(path,enclosingTypeAt(error.getPosition())));
            if(missing){
                List<Map<String,String>> methods=overrides(path).stream()
                    .filter(row->"true".equals(row.get("required"))).collect(Collectors.toList());
                if(!methods.isEmpty()){
                    StringBuilder generated=new StringBuilder();
                    for(Map<String,String> method:methods){String code=method.get("insert");generated.append(generated.length()>0&&"true".equals(method.get("enumSeparator"))?code.substring(1):code);}
                    String insert=generated.toString();
                    String label=methods.size()==1?"实现 "+methods.get(0).get("name")+"()":"实现 "+methods.size()+" 个抽象方法";
                    Map<String,String> fix=action(label,"fix",insert,Integer.parseInt(methods.get(0).get("replaceStart")));
                    fix.put("imports",methods.stream().map(row->row.getOrDefault("imports","")).filter(value->!value.isEmpty()).collect(Collectors.joining("\n")));
                    rows.add(fix);
                }
            }
        }
        long line=unit.getLineMap().getLineNumber(caret);
        for(Diagnostic<?> error:errors){
            long at=error.getPosition();
            if(at>=0&&at<=source.length()&&error.getLineNumber()==line&&"compiler.err.expected".equals(error.getCode())
                &&error.getMessage(Locale.ROOT).contains("';'")){
                rows.add(action("插入缺少的分号","fix",";",(int)at));
                break;
            }
        }
        return rows;
    }

    List<Map<String,String>> overrides(){
        TreePath path=enclosingType();
        return path==null?List.of():overrides(path);
    }

    private SourcePositions positions(){return trees.getSourcePositions();}

    private TreePath enclosingType(){return enclosingTypeAt(caret);}
    private static boolean sameType(TreePath first,TreePath second){
        return second!=null&&first.getLeaf()==second.getLeaf();
    }
    private TreePath enclosingTypeAt(long offset){
        return new TreePathScanner<TreePath,Void>(){
            @Override public TreePath visitClass(ClassTree node,Void unused){
                if(offset<positions().getStartPosition(unit,node)||offset>=positions().getEndPosition(unit,node))return null;
                TreePath inner=super.visitClass(node,unused);
                return inner!=null?inner:getCurrentPath();
            }
            @Override public TreePath reduce(TreePath last,TreePath first){return last!=null?last:first;}
        }.scan(unit,null);
    }

    private List<Map<String,String>> overrides(TreePath path){
        Element element=trees.getElement(path);
        if(!(element instanceof TypeElement)||element.getKind()==ElementKind.ANNOTATION_TYPE)return List.of();
        TypeElement owner=(TypeElement)element;
        if(owner.getSimpleName().length()==0)return List.of();
        int closing=(int)positions().getEndPosition(unit,path.getLeaf())-1;
        if(closing<0||closing>=source.length()||source.charAt(closing)!='}')return List.of();
        int insertion=JavaMemberGeneration.insertion(unit,trees,source,(ClassTree)path.getLeaf(),caret);
        if(insertion<0)return List.of();
        int begin=(int)positions().getStartPosition(unit,path.getLeaf());
        int startLine=source.lastIndexOf('\n',begin-1)+1;
        int firstContent=startLine;
        while(firstContent<begin&&(source.charAt(firstContent)==' '||source.charAt(firstContent)=='\t'))firstContent++;
        String ownerIndent=source.substring(startLine,firstContent);
        String indent=ownerIndent+"    ";

        Map<String,List<Method>> bySignature=new LinkedHashMap<>();
        for(ExecutableElement member:ElementFilter.methodsIn(elements.getAllMembers(owner))){
            if(member.getModifiers().contains(Modifier.PRIVATE))continue;
            Method method=asMember(owner,member);
            bySignature.computeIfAbsent(signatureKey(method),ignored->new ArrayList<>()).add(method);
        }
        Map<String,Method> implementations=superclassImplementations(owner);
        List<Map<String,String>> rows=new ArrayList<>();
        for(Map.Entry<String,List<Method>> entry:bySignature.entrySet()){
            List<Method> group=entry.getValue();
            if(group.stream().anyMatch(method->method.owner.equals(owner)||method.has(Modifier.FINAL)||method.has(Modifier.STATIC)))continue;
            List<Method> inherited=group.stream().filter(candidate->group.stream().noneMatch(other->
                other!=candidate&&elements.overrides(other.member,candidate.member,owner))).collect(Collectors.toList());
            if(inherited.isEmpty())continue;
            Method choice=inherited.get(0);
            for(Method candidate:inherited){
                if(types.isSubtype(candidate.signature.getReturnType(),choice.signature.getReturnType())
                    &&(!types.isSameType(candidate.signature.getReturnType(),choice.signature.getReturnType())
                        ||choice.has(Modifier.ABSTRACT)&&!candidate.has(Modifier.ABSTRACT)))choice=candidate;
            }
            // javac can expose Comparator.equals instead of the Object method that implements it.
            Method implementation=implementations.get(entry.getKey());
            if(implementation!=null&&inherited.stream().allMatch(method->canImplement(implementation,method)))choice=implementation;
            if(choice.has(Modifier.FINAL))continue;
            if(owner.getKind().isInterface()&&choice.owner.getQualifiedName().contentEquals("java.lang.Object"))continue;
            boolean required=choice.has(Modifier.ABSTRACT)&&inherited.stream().allMatch(method->method.has(Modifier.ABSTRACT));
            String receiver=inherited.size()==1||choice.signature.getThrownTypes().isEmpty()?delegateReceiver(owner,choice):null;
            boolean isPublic=group.stream().anyMatch(method->method.has(Modifier.PUBLIC));
            boolean isProtected=group.stream().anyMatch(method->method.has(Modifier.PROTECTED));
            String visibility=isPublic?"public ":isProtected?"protected ":"";
            Map<String,String> row=methodRow(owner,choice,insertion,indent,ownerIndent,visibility,owner.getKind().isInterface(),required,receiver);
            if(row!=null){JavaMemberGeneration.enumSeparator(row,unit,trees,source,(ClassTree)path.getLeaf());rows.add(row);}
        }
        rows.sort(Comparator.comparing((Map<String,String> row)->!Boolean.parseBoolean(row.get("required")))
            .thenComparing(row->row.get("label"),String.CASE_INSENSITIVE_ORDER));
        JavaGeneratedNames names=new JavaGeneratedNames(unit,trees,elements,path);
        for(Map<String,String> row:rows){names.shorten(row);if(source.contains("\r\n")){row.put("insert",row.get("insert").replace("\n","\r\n"));row.put("caret",String.valueOf(row.get("insert").length()));}}
        return rows;
    }

    private Map<String,Method> superclassImplementations(TypeElement owner){
        Map<String,Method> result=new HashMap<>();
        TypeMirror parent=owner.getSuperclass();
        if(parent.getKind()!=TypeKind.DECLARED)return result;
        TypeElement parentType=(TypeElement)((DeclaredType)parent).asElement();
        for(ExecutableElement member:ElementFilter.methodsIn(elements.getAllMembers(parentType))){
            Set<Modifier> modifiers=member.getModifiers();
            if(modifiers.contains(Modifier.PRIVATE)||modifiers.contains(Modifier.ABSTRACT)||modifiers.contains(Modifier.STATIC))continue;
            if(!modifiers.contains(Modifier.PUBLIC)&&!modifiers.contains(Modifier.PROTECTED)
                &&!elements.getPackageOf(owner).equals(elements.getPackageOf(member)))continue;
            Method method=asMember(owner,member);
            result.putIfAbsent(signatureKey(method),method);
        }
        return result;
    }

    private boolean canImplement(Method implementation,Method method){
        TypeMirror result=implementation.signature.getReturnType(),expected=method.signature.getReturnType();
        return (!method.has(Modifier.PUBLIC)||implementation.has(Modifier.PUBLIC))
            &&types.isSubsignature(implementation.signature,method.signature)
            &&(types.isSameType(result,expected)||types.isSubtype(result,expected));
    }

    private Method asMember(TypeElement owner,ExecutableElement member){
        return new Method(member,(ExecutableType)types.asMemberOf((DeclaredType)owner.asType(),member));
    }
    private String signatureKey(Method method){
        return method.member.getSimpleName()+method.signature.getParameterTypes().stream()
            .map(type->types.erasure(type).toString()).collect(Collectors.joining(",","(",")"));
    }

    private String delegateReceiver(TypeElement owner,Method method){
        if(method.has(Modifier.ABSTRACT))return null;
        if(!method.owner.getKind().isInterface())return "super";
        TypeMirror origin=types.erasure(method.owner.asType());
        TypeMirror parent=owner.getSuperclass();
        if(parent.getKind()==TypeKind.DECLARED&&types.isSubtype(types.erasure(parent),origin))return "super";
        for(TypeMirror direct:owner.getInterfaces()){
            if(direct.getKind()!=TypeKind.DECLARED||!types.isSubtype(types.erasure(direct),origin))continue;
            boolean redundant=owner.getInterfaces().stream().anyMatch(other->
                !types.isSameType(other,direct)&&types.isSubtype(types.erasure(other),types.erasure(direct)));
            if(!redundant)return ((TypeElement)((DeclaredType)direct).asElement()).getQualifiedName()+".super";
        }
        return null;
    }

    private Map<String,String> methodRow(TypeElement owner,Method method,int at,String indent,String ownerIndent,String visibility,boolean defaultMethod,boolean required,String receiver){
        ExecutableType signature=method.signature;
        JavaTypeSpelling spelling=new JavaTypeSpelling(owner,signature);
        List<? extends TypeMirror> parameterTypes=signature.getParameterTypes();
        StringJoiner parameters=new StringJoiner(", "),arguments=new StringJoiner(", ");
        for(int i=0;i<parameterTypes.size();i++){
            TypeMirror type=parameterTypes.get(i);
            if(type.getKind()==TypeKind.ERROR)return null;
            String parameter=spelling.type(type);
            if(method.member.isVarArgs()&&i==parameterTypes.size()-1)parameter=spelling.type(((ArrayType)type).getComponentType())+"...";
            String name=method.member.getParameters().get(i).getSimpleName().toString();
            parameters.add(parameter+" "+name);
            arguments.add(name);
        }
        String typeParameters=spelling.parameters(signature);
        TypeMirror result=signature.getReturnType();
        if(result.getKind()==TypeKind.ERROR)return null;
        String name=method.member.getSimpleName().toString();
        String header=name+"("+parameters+")";
        String thrown=receiver!=null&&!signature.getThrownTypes().isEmpty()
            ?" throws "+signature.getThrownTypes().stream().map(spelling::type).collect(Collectors.joining(", ")):"";
        String statement=receiver!=null?(result.getKind()==TypeKind.VOID?"":"return ")+receiver+"."+name+"("+arguments+");"
            :"throw new java.lang.UnsupportedOperationException(\"TODO\");";
        String suffix=at<source.length()&&source.charAt(at)=='}'?ownerIndent:"";
        String insert="\n"+indent+"@java.lang.Override\n"+indent+visibility+(defaultMethod?"default ":"")+typeParameters
            +spelling.type(result)+" "+header+thrown+" {\n"+indent+"    "+statement+"\n"+indent+"}\n"+suffix;
        String label=header.replace("java.lang.","")+" : "+result.toString().replace("java.lang.","");
        Map<String,String> row=action(label,"override",insert,at);
        row.put("name",name);
        row.put("detail",method.owner.getQualifiedName().toString());
        row.put("required",String.valueOf(required));
        row.put("icon",required?"methodAbstract":"method");
        return row;
    }

    private static Map<String,String> action(String label,String kind,String insert,int position){
        Map<String,String> row=new HashMap<>();
        row.put("label",label);
        row.put("kind",kind);
        row.put("insert",insert);
        row.put("replaceStart",String.valueOf(position));
        row.put("replaceEnd",String.valueOf(position));
        row.put("caret",String.valueOf(insert.length()));
        return row;
    }

    private static final class Method {
        final ExecutableElement member;
        final ExecutableType signature;
        final TypeElement owner;
        Method(ExecutableElement member,ExecutableType signature){
            this.member=member;this.signature=signature;owner=(TypeElement)member.getEnclosingElement();
        }
        boolean has(Modifier modifier){return member.getModifiers().contains(modifier);}
    }
}
