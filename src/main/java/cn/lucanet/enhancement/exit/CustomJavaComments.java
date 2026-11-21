package cn.lucanet.enhancement.exit;

import com.sun.source.tree.*;
import com.sun.source.util.*;
import java.net.URI;
import java.util.*;
import java.util.function.BiFunction;
import javax.tools.*;

/** Restores declaration comments absent from native parts; native method bodies stay authoritative. */
public final class CustomJavaComments implements BiFunction<String,String,Map<String,String>> {
    @Override public Map<String,String> apply(String stored,String current){
        try {
            Document original=new Document(stored),updated=new Document(current);
            List<Comment> comments=comments(stored);
            comments.removeIf(comment->original.payloads.stream().anyMatch(range->range.contains(comment.start,comment.end)));
            if(comments.isEmpty())return Map.of("source",current);
            Map<Integer,List<String>> insertions=new TreeMap<>();
            for(Comment comment:comments){
                int at=updated.typeStart;
                if(comment.start<original.typeStart){
                    if(comment.start<original.packageStart)at=0;
                    else for(Declaration entry:original.imports)if(comment.end<=entry.end){
                        Declaration match=find(updated.imports,entry);
                        if(match!=null)at=match.start;
                        break;
                    }
                }else{
                    Declaration owner=null;
                    for(Declaration member:original.members){
                        if(comment.start>=member.start&&comment.end<=member.end){owner=member;break;}
                        if(comment.end<=member.start){owner=member;break;}
                    }
                    Declaration match=owner==null?null:find(updated.members,owner);
                    at=match==null?updated.closing:match.start;
                }
                insertions.computeIfAbsent(at,ignored->new ArrayList<>()).add(stored.substring(comment.start,comment.end));
            }
            StringBuilder result=new StringBuilder(current);
            List<Integer> offsets=new ArrayList<>(insertions.keySet());Collections.reverse(offsets);
            for(int at:offsets){
                int line=current.lastIndexOf('\n',at-1)+1;
                String indent=current.substring(line,at);if(!indent.isBlank())indent="";
                String newline=current.contains("\r\n")?"\r\n":"\n";
                result.insert(at,String.join(newline+indent,insertions.get(at))+newline+indent);
            }
            return Map.of("source",result.toString());
        }catch(Exception failure){
            // Incomplete native drafts remain repairable. Keep any missing comments visibly at
            // the top rather than silently discarding them or overwriting native code with old code.
            Map<String,Integer> counts=new HashMap<>();
            for(Comment comment:comments(current))counts.merge(current.substring(comment.start,comment.end),1,Integer::sum);
            StringBuilder retained=new StringBuilder();
            for(Comment comment:comments(stored)){
                String text=stored.substring(comment.start,comment.end);int count=counts.getOrDefault(text,0);
                if(count>0)counts.put(text,count-1);else retained.append(text).append('\n');
            }
            return Map.of("source",retained+current,"warning","原生端源码尚不完整，未能定位的原有注释已保留在文件开头，请检查后保存。");
        }
    }

    private static Declaration find(List<Declaration> entries,Declaration original){
        for(Declaration entry:entries)if(entry.key.equals(original.key))return entry;
        // Parameter edits change a method's signature, but an unambiguous name still identifies it.
        Declaration only=null;
        for(Declaration entry:entries)if(entry.name.equals(original.name)){
            if(only!=null)return null;only=entry;
        }
        return only;
    }
    private static final class Range {
        final int start,end;
        Range(int start,int end){this.start=start;this.end=end;}
        boolean contains(int from,int to){return from>=start&&to<=end;}
    }
    private static final class Declaration {
        final String key,name;final int start,end;
        Declaration(String key,String name,int start,int end){this.key=key;this.name=name;this.start=start;this.end=end;}
    }
    private static final class Document {
        final List<Declaration> members=new ArrayList<>(),imports=new ArrayList<>();
        final List<Range> payloads=new ArrayList<>();
        int typeStart,closing,packageStart;
        Document(String source)throws Exception{
            JavaCompiler compiler=ToolProvider.getSystemJavaCompiler();
            DiagnosticCollector<JavaFileObject> diagnostics=new DiagnosticCollector<>();
            try(StandardJavaFileManager manager=compiler.getStandardFileManager(diagnostics,Locale.ROOT,null)){
                JavaFileObject file=new SimpleJavaFileObject(URI.create("string:///Source.java"),JavaFileObject.Kind.SOURCE){
                    @Override public CharSequence getCharContent(boolean ignored){return source;}
                };
                JavacTask task=(JavacTask)compiler.getTask(null,manager,diagnostics,List.of("-proc:none","-Xlint:none"),null,List.of(file));
                CompilationUnitTree unit=task.parse().iterator().next();
                if(diagnostics.getDiagnostics().stream().anyMatch(d->d.getKind()==Diagnostic.Kind.ERROR))throw new IllegalArgumentException("incomplete source");
                SourcePositions positions=Trees.instance(task).getSourcePositions();
                packageStart=unit.getPackageName()==null?0:(int)positions.getStartPosition(unit,unit.getPackageName())-8;
                ClassTree type=null;
                for(Tree declaration:unit.getTypeDecls())if(declaration instanceof ClassTree){
                    if(type!=null)throw new IllegalArgumentException("multiple native types");type=(ClassTree)declaration;
                }
                if(type==null)throw new IllegalArgumentException("missing native type");
                typeStart=(int)positions.getStartPosition(unit,type);closing=(int)positions.getEndPosition(unit,type)-1;
                if(typeStart<0||closing<typeStart)throw new IllegalArgumentException("missing range");
                for(ImportTree entry:unit.getImports())imports.add(new Declaration(entry.toString(),entry.toString(),(int)positions.getStartPosition(unit,entry),(int)positions.getEndPosition(unit,entry)));
                for(Tree member:type.getMembers()){
                    String key,name;Tree payload=null;
                    if(member instanceof MethodTree){
                        MethodTree method=(MethodTree)member;name="method:"+method.getName();StringJoiner parameters=new StringJoiner(",","(",")");
                        for(VariableTree parameter:method.getParameters())parameters.add(parameter.getType().toString().replace("java.lang.","").replaceAll("\\s+",""));
                        key=name+parameters;payload=method.getBody();
                    }else if(member instanceof VariableTree){
                        VariableTree field=(VariableTree)member;key=name="field:"+field.getName();payload=field.getInitializer();
                    }else continue;
                    members.add(new Declaration(key,name,(int)positions.getStartPosition(unit,member),(int)positions.getEndPosition(unit,member)));
                    if(payload!=null)payloads.add(new Range((int)positions.getStartPosition(unit,payload),(int)positions.getEndPosition(unit,payload)));
                }
            }
        }
    }
    private static final class Comment {
        final int start,end;
        Comment(int start,int end){this.start=start;this.end=end;}
    }
    private static List<Comment> comments(String source){
        List<Comment> result=new ArrayList<>();
        for(int i=0;i<source.length();){
            if(source.startsWith("//",i)||source.startsWith("/*",i)){
                boolean block=source.startsWith("/*",i);int end=source.indexOf(block?"*/":"\n",i+2);
                end=end<0?source.length():end+(block?2:0);
                result.add(new Comment(i,end));i=end;
            }else if(source.charAt(i)=='"'||source.charAt(i)=='\''){
                boolean block=source.startsWith("\"\"\"",i);char quote=source.charAt(i);i+=block?3:1;
                while(i<source.length()){
                    if(source.charAt(i)=='\\'){i=Math.min(source.length(),i+2);continue;}
                    if(block?source.startsWith("\"\"\"",i):source.charAt(i)==quote){i+=block?3:1;break;}i++;
                }
            }else i++;
        }
        return result;
    }
}
