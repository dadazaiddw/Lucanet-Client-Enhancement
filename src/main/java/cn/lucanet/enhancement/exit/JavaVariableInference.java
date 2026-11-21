package cn.lucanet.enhancement.exit;

import com.sun.source.tree.*;
import com.sun.source.util.*;
import java.util.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.*;
import javax.lang.model.type.*;
import javax.lang.model.util.*;

/** Introduce-variable proposals use attributed, source-denotable types, never textual guesses. */
final class JavaVariableInference {
    private final CompilationUnitTree unit;
    private final Trees trees;
    private final Elements elements;
    private final Types types;
    private final Scope scope;
    private final TreePath expression;

    JavaVariableInference(CompilationUnitTree unit,Trees trees,Elements elements,Types types,Scope scope,TreePath expression){
        this.unit=unit;this.trees=trees;this.elements=elements;this.types=types;this.scope=scope;this.expression=expression;
    }

    Map<String,String> declaration(String initializer,boolean fullUnit){
        if(initializer.trim().equals("super")||initializer.trim().endsWith(".super"))return null;
        TypeMirror type=trees.getTypeMirror(expression);
        String spelling=spell(type,false,0);
        if(spelling==null)return null;
        Map<String,String> row=new LinkedHashMap<>();row.put("insert",spelling);
        new JavaGeneratedNames(unit,trees,elements,expression).shorten(row,fullUnit);
        spelling=row.get("insert");
        Set<String> occupied=new HashSet<>();
        if(scope!=null)for(Scope cursor=scope;cursor!=null;cursor=cursor.getEnclosingScope())
            for(Element local:cursor.getLocalElements())occupied.add(local.getSimpleName().toString());
        // Include later declarations and referenced fields: introducing a local must not rebind them.
        new TreeScanner<Void,Void>(){
            @Override public Void visitVariable(VariableTree node,Void unused){occupied.add(node.getName().toString());return super.visitVariable(node,unused);}
            @Override public Void visitIdentifier(IdentifierTree node,Void unused){occupied.add(node.getName().toString());return null;}
        }.scan(unit,null);
        String base=expressionName(expression.getLeaf());
        if(base==null||!identifier(base))base=typeName(type,0);
        if(!identifier(base))base="result";
        String name=base;for(int suffix=1;occupied.contains(name);suffix++)name=base+suffix;
        row.put("insert",spelling+" "+name+" = "+initializer+";");
        row.put("selectionStart",String.valueOf(spelling.length()+1));
        row.put("caret",String.valueOf(spelling.length()+1+name.length()));
        return row;
    }

    private String spell(TypeMirror type,boolean argument,int depth){
        if(type==null||depth>20)return null;
        switch(type.getKind()){
            case BOOLEAN:case BYTE:case SHORT:case INT:case LONG:case CHAR:case FLOAT:case DOUBLE:return type.getKind().name().toLowerCase(Locale.ROOT);
            case ARRAY:{String component=spell(((ArrayType)type).getComponentType(),false,depth+1);return component==null?null:(argument&&!exact(type,depth+1)?"? extends ":"")+component+"[]";}
            case TYPEVAR:{
                TypeVariable variable=(TypeVariable)type;String name=variable.asElement().getSimpleName().toString();
                if(identifier(name))return name;
                if(argument){
                    TypeMirror lower=variable.getLowerBound();
                    if(lower!=null&&lower.getKind()!=TypeKind.NULL){String bound=spell(lower,false,depth+1);return bound==null||!exact(lower,depth+1)?"?":"? super "+bound;}
                    String bound=spell(variable.getUpperBound(),false,depth+1);
                    return bound==null||bound.equals("java.lang.Object")?"?":"? extends "+bound;
                }
                return spell(variable.getUpperBound(),false,depth+1);
            }
            case WILDCARD:{
                WildcardType wildcard=(WildcardType)type;
                TypeMirror bound=wildcard.getSuperBound()!=null?wildcard.getSuperBound():wildcard.getExtendsBound();
                if(!argument)return bound==null||wildcard.getSuperBound()!=null?"java.lang.Object":spell(bound,false,depth+1);
                if(wildcard.getSuperBound()!=null&&!exact(bound,depth+1))return "?";
                String text=bound==null?null:spell(bound,false,depth+1);
                return text==null?"?":"? "+(wildcard.getSuperBound()!=null?"super ":"extends ")+text;
            }
            case INTERSECTION:
                for(TypeMirror bound:((IntersectionType)type).getBounds()){String text=spell(bound,false,depth+1);if(text!=null)return argument?"? extends "+text:text;}
                return "java.lang.Object";
            case DECLARED:{
                DeclaredType declared=(DeclaredType)type;TypeElement element=(TypeElement)declared.asElement();
                boolean accessible=element.getNestingKind()!=NestingKind.ANONYMOUS;
                if(accessible&&scope!=null)try{accessible=trees.isAccessible(scope,element);}catch(IllegalArgumentException incomplete){accessible=false;}
                if(!accessible){
                    String projected=supertype(type,depth+1);
                    return !argument?projected:projected==null||projected.equals("java.lang.Object")?"?":"? extends "+projected;
                }
                String name=element.getNestingKind()==NestingKind.LOCAL?element.getSimpleName().toString():element.getQualifiedName().toString();
                if(element.getNestingKind()!=NestingKind.LOCAL&&declared.getEnclosingType().getKind()==TypeKind.DECLARED){
                    String owner=spell(declared.getEnclosingType(),false,depth+1);if(owner==null)return null;
                    name=owner+"."+element.getSimpleName();
                }
                if(name.isEmpty())return null;
                if(!declared.getTypeArguments().isEmpty()){
                    StringJoiner arguments=new StringJoiner(", ","<",">");
                    for(TypeMirror parameter:declared.getTypeArguments()){String text=spell(parameter,true,depth+1);if(text==null)return null;arguments.add(text);}
                    name+=arguments;
                }
                return argument&&!exact(type,depth+1)?"? extends "+name:name;
            }
            default:return null;
        }
    }
    private String supertype(TypeMirror type,int depth){
        for(TypeMirror parent:types.directSupertypes(type)){
            String text=spell(parent,false,depth+1);if(text!=null&&!text.equals("java.lang.Object"))return text;
        }
        return "java.lang.Object";
    }
    private boolean exact(TypeMirror type,int depth){
        if(type==null||depth>20)return false;
        switch(type.getKind()){
            case TYPEVAR:return identifier(((TypeVariable)type).asElement().getSimpleName().toString());
            case ARRAY:return exact(((ArrayType)type).getComponentType(),depth+1);
            case WILDCARD:{WildcardType wildcard=(WildcardType)type;return (wildcard.getExtendsBound()==null||exact(wildcard.getExtendsBound(),depth+1))&&(wildcard.getSuperBound()==null||exact(wildcard.getSuperBound(),depth+1));}
            case DECLARED:{
                DeclaredType declared=(DeclaredType)type;TypeElement element=(TypeElement)declared.asElement();
                if(element.getNestingKind()==NestingKind.ANONYMOUS)return false;
                if(scope!=null)try{if(!trees.isAccessible(scope,element))return false;}catch(IllegalArgumentException incomplete){return false;}
                for(TypeMirror argument:declared.getTypeArguments())if(!exact(argument,depth+1))return false;
                return declared.getEnclosingType().getKind()!=TypeKind.DECLARED||exact(declared.getEnclosingType(),depth+1);
            }
            default:return type.getKind().isPrimitive();
        }
    }
    private static boolean identifier(String name){return name!=null&&SourceVersion.isIdentifier(name)&&!SourceVersion.isKeyword(name)&&!Set.of("var","yield","record","sealed","permits","_").contains(name);}
    private static String expressionName(Tree node){
        if(node instanceof ParenthesizedTree)return expressionName(((ParenthesizedTree)node).getExpression());
        if(node instanceof TypeCastTree)return expressionName(((TypeCastTree)node).getExpression());
        if(node instanceof IdentifierTree){String name=((IdentifierTree)node).getName().toString();return name.equals("this")||name.equals("super")?null:name;}
        if(node instanceof MemberSelectTree){String name=((MemberSelectTree)node).getIdentifier().toString();return name.equals("class")?null:decap(name);}
        if(node instanceof MethodInvocationTree){
            ExpressionTree method=((MethodInvocationTree)node).getMethodSelect();
            String name=method instanceof MemberSelectTree?((MemberSelectTree)method).getIdentifier().toString():method.toString();
            for(String prefix:List.of("get","is","find","create","load","fetch","read","build","compute","to","as"))
                if(name.startsWith(prefix)&&name.length()>prefix.length()&&Character.isUpperCase(name.charAt(prefix.length())))return decap(name.substring(prefix.length()));
            if(Set.of("get","of","ofNullable","valueOf","newInstance","next","iterator","stream","collect","build","copy","clone","empty","emptyList","emptySet","emptyMap").contains(name))return null;
            return decap(name);
        }
        if(node instanceof ArrayAccessTree){String name=expressionName(((ArrayAccessTree)node).getExpression());return name==null?null:singular(name);}
        return null;
    }
    private String typeName(TypeMirror type,int depth){
        if(type==null||depth>10)return "result";
        switch(type.getKind()){
            case BOOLEAN:return "condition";
            case CHAR:return "character";
            case BYTE:case SHORT:case INT:case LONG:case FLOAT:case DOUBLE:return "number";
            case ARRAY:return plural(elementName(((ArrayType)type).getComponentType(),depth+1));
            case TYPEVAR:return typeName(((TypeVariable)type).getUpperBound(),depth+1);
            case WILDCARD:return typeName(((WildcardType)type).getExtendsBound(),depth+1);
            case INTERSECTION:return typeName(((IntersectionType)type).getBounds().get(0),depth+1);
            case DECLARED:{
                DeclaredType declared=(DeclaredType)type;TypeElement element=(TypeElement)declared.asElement();String name=element.getSimpleName().toString();
                if(element.getNestingKind()==NestingKind.ANONYMOUS){
                    for(TypeMirror parent:types.directSupertypes(type))if(!parent.toString().equals("java.lang.Object"))return typeName(parent,depth+1);
                    return "object";
                }
                if(element.getQualifiedName().contentEquals("java.lang.String"))return "text";
                if(element.getQualifiedName().contentEquals("java.lang.Class"))return "type";
                if(!declared.getTypeArguments().isEmpty()&&subtype(type,"java.lang.Iterable"))return plural(elementName(declared.getTypeArguments().get(0),depth+1));
                return decap(name);
            }
            default:return "result";
        }
    }
    private boolean subtype(TypeMirror type,String parent){TypeElement target=elements.getTypeElement(parent);return target!=null&&types.isSubtype(types.erasure(type),types.erasure(target.asType()));}
    private String elementName(TypeMirror type,int depth){
        if(type!=null&&type.getKind()==TypeKind.DECLARED){TypeElement element=(TypeElement)((DeclaredType)type).asElement();
            if(element.getQualifiedName().contentEquals("java.lang.String"))return "string";
            if(subtype(type,"java.lang.Iterable"))return decap(element.getSimpleName().toString());
        }
        return typeName(type,depth);
    }
    private static String decap(String name){
        if(name.isEmpty())return name;int end=1;
        while(end<name.length()&&Character.isUpperCase(name.charAt(end)))end++;
        if(end>1&&end<name.length())end--;
        return name.substring(0,end).toLowerCase(Locale.ROOT)+name.substring(end);
    }
    private static String plural(String name){
        if(name.endsWith("s")||name.endsWith("x")||name.endsWith("ch")||name.endsWith("sh"))return name+"es";
        if(name.endsWith("y")&&name.length()>1&&"aeiou".indexOf(name.charAt(name.length()-2))<0)return name.substring(0,name.length()-1)+"ies";
        return name+"s";
    }
    private static String singular(String name){return name.endsWith("ies")?name.substring(0,name.length()-3)+"y":name.endsWith("s")&&!name.endsWith("ss")?name.substring(0,name.length()-1):name+"Item";}
}
