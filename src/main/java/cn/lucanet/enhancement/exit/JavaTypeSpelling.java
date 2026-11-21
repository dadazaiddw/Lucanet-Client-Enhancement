package cn.lucanet.enhancement.exit;

import java.util.*;
import java.util.stream.Collectors;
import javax.lang.model.element.*;
import javax.lang.model.type.*;

/** Renders attributed signatures without conflating equally named type variables. */
final class JavaTypeSpelling {
    private final Map<Element,String> variables=new HashMap<>();

    JavaTypeSpelling(TypeElement owner,ExecutableType signature){
        Set<String> occupied=new HashSet<>();
        for(Element scope=owner;scope!=null;scope=scope.getEnclosingElement()){
            if(scope instanceof TypeElement)for(TypeParameterElement variable:((TypeElement)scope).getTypeParameters())occupied.add(variable.getSimpleName().toString());
            if(scope instanceof ExecutableElement)for(TypeParameterElement variable:((ExecutableElement)scope).getTypeParameters())occupied.add(variable.getSimpleName().toString());
        }
        Set<String> reserved=new HashSet<>(occupied);
        for(TypeVariable variable:signature.getTypeVariables())reserved.add(variable.asElement().getSimpleName().toString());
        for(TypeVariable variable:signature.getTypeVariables()){
            String original=variable.asElement().getSimpleName().toString(),name=original;
            if(occupied.contains(name))for(int suffix=1;;suffix++)if(reserved.add(original+suffix)){name=original+suffix;break;}
            occupied.add(name);variables.put(variable.asElement(),name);
        }
    }

    String parameters(ExecutableType signature){
        StringJoiner result=new StringJoiner(", ","<","> ").setEmptyValue("");
        for(TypeVariable variable:signature.getTypeVariables()){
            String bound=type(variable.getUpperBound());
            result.add(type(variable)+(bound.equals("java.lang.Object")?"":" extends "+bound));
        }
        return result.toString();
    }

    String type(TypeMirror type){
        String annotations=type.getAnnotationMirrors().stream().map(Object::toString).collect(Collectors.joining(" "));
        if(!annotations.isEmpty())annotations+=" ";
        switch(type.getKind()){
            case TYPEVAR:
                Element variable=((TypeVariable)type).asElement();
                return annotations+variables.getOrDefault(variable,variable.getSimpleName().toString());
            case ARRAY:return type(((ArrayType)type).getComponentType())+(annotations.isEmpty()?"":" "+annotations)+"[]";
            case DECLARED:
                DeclaredType declared=(DeclaredType)type;TypeElement element=(TypeElement)declared.asElement();
                String name=declared.getEnclosingType().getKind()==TypeKind.DECLARED
                    ?type(declared.getEnclosingType())+"."+annotations+element.getSimpleName():qualified(element,annotations);
                return name+(declared.getTypeArguments().isEmpty()?"":declared.getTypeArguments().stream().map(this::type).collect(Collectors.joining(", ","<",">")));
            case WILDCARD:
                WildcardType wildcard=(WildcardType)type;
                return annotations+"?"+(wildcard.getExtendsBound()!=null?" extends "+type(wildcard.getExtendsBound()):wildcard.getSuperBound()!=null?" super "+type(wildcard.getSuperBound()):"");
            case INTERSECTION:return ((IntersectionType)type).getBounds().stream().map(this::type).collect(Collectors.joining(" & "));
            default:return type.toString();
        }
    }
    private static String qualified(TypeElement element,String annotations){
        String name=element.getQualifiedName().toString();int dot=name.lastIndexOf('.')+1;
        return name.substring(0,dot)+annotations+name.substring(dot);
    }
}
