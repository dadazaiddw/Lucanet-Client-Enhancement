package cn.lucanet.enhancement.exit;

import com.sun.source.tree.*;
import com.sun.source.util.*;
import java.io.*;
import java.net.URI;
import java.nio.file.*;
import java.util.*;
import java.util.function.Function;
import java.util.regex.*;
import java.util.zip.*;
import javax.lang.model.element.*;
import javax.lang.model.type.*;
import javax.lang.model.util.*;
import javax.tools.*;

/** Read-only javac analysis. No class generation, annotation processors, or user-code execution.
 * Loaded behind a platform-parent classloader so protocol-1 agents can also load jdk.compiler. */
public final class JavaIntelligence implements Function<Map<String,Object>,List<Map<String,String>>>, AutoCloseable {
    private static final String MARK = "__lucanet_completion__";
    private static final Pattern TYPE_NAME=Pattern.compile("\\b(?:class|interface|enum|record)\\s+([\\p{javaJavaIdentifierPart}]+)");
    private static final Pattern PACKAGE_NAME=Pattern.compile("\\bpackage\\s+([\\p{javaJavaIdentifierPart}.]+)\\s*;");
    private final JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    private StandardJavaFileManager files;
    private String classpath;
    private Object libraryRevision;
    private final SortedMap<String,SortedSet<String>> classes = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    private boolean indexed;
    private Map<String,String> companionSources=Collections.emptyMap();
    private Set<String> customNames=Collections.emptySet();
    private Map<?,?> lastCompanions;
    private List<String> lastPartKey;
    private int partFrom=-1,partTo=-1;

    @Override public synchronized List<Map<String,String>> apply(Map<String,Object> input) {
        if (compiler == null) throw new IllegalStateException("当前 Lucanet JDK 未提供 Java 编译器");
        try {
            configure((String) input.getOrDefault("classpath", ""),input.get("libraryRevision"));
            String operation = (String) input.getOrDefault("operation", "complete");
            Map<?,?> companions=input.get("sources") instanceof Map?(Map<?,?>)input.get("sources"):Map.of();
            if(!companions.equals(lastCompanions)){
                companionSources=new LinkedHashMap<>();
                for(Object candidate:companions.values())if(candidate instanceof String){
                    String source=(String)candidate;
                    companionSources.put(sourceName(source),source);
                }
                customNames=new HashSet<>(companionSources.keySet());
                lastCompanions=new LinkedHashMap<>(companions);
            }
            if (operation.equals("index")) { index(); return Collections.emptyList(); }
            String text = (String) input.getOrDefault("text", "");
            int caret = Math.max(0,Math.min(text.length(), (Integer) input.getOrDefault("caret", text.length())));
            int start = caret;
            while (start > 0 && Character.isJavaIdentifierPart(text.charAt(start - 1))) start--;
            int end = caret;
            while (end < text.length() && Character.isJavaIdentifierPart(text.charAt(end))) end++;
            String prefix = text.substring(start, caret);
            boolean member = start > 0 && text.charAt(start - 1) == '.';
            boolean quickFix=operation.equals("quickFix");
            boolean imports = operation.equals("import")||quickFix;
            Map<String,Map<String,String>> result = new LinkedHashMap<>();
            String[] embedded = embed(input, text);
            int base = Integer.parseInt(embedded[1]);
            if(operation.equals("parameters"))return parameters(text,caret,embedded);
            if(operation.equals("organize"))return organize(input,embedded[0]);
            Analysis attributed=null;
            List<Map<String,String>> fixes=List.of();
            if(quickFix||operation.equals("diagnostics")||operation.equals("override")||operation.startsWith("generate:")){
                DiagnosticCollector<JavaFileObject> collector=new DiagnosticCollector<>();
                attributed=analyze(embedded[0],true,collector,true);
                List<Diagnostic<? extends JavaFileObject>> errors=new ArrayList<>();
                for(Diagnostic<? extends JavaFileObject> diagnostic:collector.getDiagnostics())
                    if(diagnostic.getKind()==Diagnostic.Kind.ERROR&&diagnostic.getSource()!=null&&
                        diagnostic.getSource().toUri().equals(attributed.unit.getSourceFile().toUri()))errors.add(diagnostic);
                if(operation.equals("diagnostics"))return JavaTypeActions.diagnostics(errors,text.length());
                JavaTypeActions actions=new JavaTypeActions(attributed.unit,attributed.trees,attributed.elements,attributed.types,text,caret);
                if(operation.startsWith("generate:"))return new JavaMemberGeneration(attributed.unit,attributed.trees,attributed.elements,attributed.types,text,caret).apply(operation.substring(9),input);
                if(operation.equals("override"))return actions.overrides();
                fixes=actions.fixes(errors);
            }
            if (!imports) {
                String suffix = end < text.length() && text.charAt(end) == '(' ? "" : "()";
                String source = embedded[0].substring(0, base + start) + MARK + suffix + embedded[0].substring(base + end);
                Analysis analysis = analyze(source, true);
                TreePath path=marker(analysis);
                if(!suffix.isEmpty()&&(path==null||path.getLeaf() instanceof MemberSelectTree&&analysis.trees.getTypeMirror(new TreePath(path,((MemberSelectTree)path.getLeaf()).getExpression()))==null)) {
                    // Annotation defaults and declaration positions reject a synthetic method call.
                    source=embedded[0].substring(0,base+start)+MARK+embedded[0].substring(base+end);
                    analysis=analyze(source,true);path=marker(analysis);
                }
                if(path!=null)attributed=analysis;
                if (path != null) {
                    analysis.expected=expectedType(analysis,path);
                    Scope scope;
                    try { scope=analysis.trees.getScope(path); } catch(RuntimeException incomplete){scope=null;}
                    if(scope!=null && member && path.getLeaf() instanceof MemberSelectTree) {
                        TreePath receiver = new TreePath(path, ((MemberSelectTree) path.getLeaf()).getExpression());
                        TypeMirror type = analysis.trees.getTypeMirror(receiver);
                        Element owner = analysis.trees.getElement(receiver);
                        boolean staticOnly = owner instanceof TypeElement;
                        addMembers(result, analysis, scope, type, staticOnly, prefix);
                        addPostfix(result,analysis,scope,receiver,text,base,start,end,prefix,staticOnly);
                        if (type != null && type.getKind() == TypeKind.ARRAY)
                            add(result, "length", "length : int", "length", "", "field", 6);
                    } else if(scope!=null) {
                        Set<String> variables = new HashSet<>();
                        for (Scope cursor = scope; cursor != null; cursor = cursor.getEnclosingScope()) {
                            for (Element element : cursor.getLocalElements()) {
                                if (element instanceof VariableElement && !variables.add(element.getSimpleName().toString())) continue;
                                addElement(result, analysis, element, null, prefix);
                            }
                        }
                        if (scope.getEnclosingClass() != null)
                            addMembers(result, analysis, scope, scope.getEnclosingClass().asType(),
                                    scope.getEnclosingMethod() != null && scope.getEnclosingMethod().getModifiers().contains(Modifier.STATIC), prefix);
                    }
                }
            }
            if (!member && !prefix.isEmpty()) {
                if (!indexed) index();
                int count = 0;
                Analysis types = attributed!=null?attributed:analyze(embedded[0], true);
                List<String> qualifiedNames=new ArrayList<>();
                if(imports)qualifiedNames.addAll(classes.getOrDefault(prefix,Collections.emptySortedSet()));
                else for(Map.Entry<String,SortedSet<String>> entry:classes.entrySet())
                    if(matches(entry.getKey(),prefix))qualifiedNames.addAll(entry.getValue());
                qualifiedNames.removeIf(name->imports&&!simpleName(name).equals(prefix));
                for(String name:customNames)
                    if(imports?simpleName(name).equals(prefix):matches(simpleName(name),prefix))qualifiedNames.add(name);
                qualifiedNames.sort(Comparator.comparingInt((String q)->matchRank(simpleName(q),prefix)).thenComparingInt(q->classRank(q,types.unit))
                        .thenComparing(q->simpleName(q),String.CASE_INSENSITIVE_ORDER).thenComparing(q->q));
                String currentPackage=types.unit.getPackageName()==null?"":types.unit.getPackageName().toString();
                for(String qualified:qualifiedNames) {
                        TypeElement type;
                        try { type = types.elements.getTypeElement(qualified); }
                        catch (RuntimeException | LinkageError badLibrary) { continue; }
                        if (type==null||!publicType(type) && !(customNames.contains(qualified)&&packageName(qualified).equals(currentPackage))) continue;
                        String name=simpleName(qualified);
                        Map<String,String> value=add(result,name,name+" — "+qualified,name,qualified,type!=null&&type.getKind().isInterface()?"interface":"class",name.length());
                        value.put("rank",String.valueOf(classRank(qualified,types.unit)));value.put("detail",packageName(qualified));
                        typeParameters(value,type);
                        iconMetadata(value,types,type);
                        if(customNames.contains(qualified))value.put("origin","当前脚本 Class");
                        if (++count >= 100) break;
                }
            }
            if(!member && !imports)for(String keyword:("abstract assert boolean break byte case catch char class const continue default do double else enum extends final finally float for if implements import instanceof int interface long native new null package private protected public return short static strictfp super switch synchronized this throw throws transient true false try void volatile while var").split(" "))
                if(matches(keyword,prefix))add(result,keyword,keyword+" — Java",keyword,"","keyword",keyword.length());
            List<Map<String,String>> values = new ArrayList<>(result.values());
            values.removeIf(value -> !matches(value.get("name"), prefix));
            values.sort(Comparator.comparingInt((Map<String,String> value) -> matchRank(value.get("name"),prefix))
                    .thenComparingInt(value->Integer.parseInt(value.getOrDefault("typeRank","1")))
                    .thenComparingInt(value->Integer.parseInt(value.getOrDefault("rank","50")))
                    .thenComparing(value -> value.get("name"), String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(value -> value.get("label")));
            values.addAll(0,fixes);
            if(Boolean.TRUE.equals(input.get("compilationUnit"))&&values.stream().anyMatch(row->!row.getOrDefault("import","").isEmpty())){
                Analysis context=attributed!=null?attributed:analyze(embedded[0],true);
                TreePath location=JavaGeneratedNames.at(context.unit,context.trees,caret);
                JavaGeneratedNames names=new JavaGeneratedNames(context.unit,context.trees,context.elements,location);
                for(Map<String,String> value:values){
                    String qualified=value.getOrDefault("import","");if(qualified.isEmpty())continue;
                    // Candidates are alternatives: each gets an independent import plan.
                    Map<String,String> plan=new HashMap<>();plan.put("insert",qualified);
                    new JavaGeneratedNames(names).shorten(plan);
                    value.put("unitReference",plan.get("insert"));value.put("imports",plan.getOrDefault("imports",""));
                }
            }
            return values.size() > 150 ? values.subList(0,150) : values;
        } catch (IOException failure) { throw new UncheckedIOException(failure); }
    }

    private void configure(String next,Object nextRevision) throws IOException {
        if (files != null && Objects.equals(classpath,next)&&Objects.equals(libraryRevision,nextRevision)) return;
        if (files != null) files.close();
        files = compiler.getStandardFileManager(null, Locale.ROOT, java.nio.charset.StandardCharsets.UTF_8);
        List<File> paths = new ArrayList<>();
        for (String path : next.split(Pattern.quote(File.pathSeparator))) if (!path.isEmpty() && Files.exists(Path.of(path))) paths.add(new File(path));
        files.setLocation(StandardLocation.CLASS_PATH, paths);
        classpath = next; libraryRevision=nextRevision; indexed = false; classes.clear();
    }

    /** Use the native class structure, replacing only the active member in the analysis copy. */
    private String[] embed(Map<String,Object> input, String text) throws IOException {
        if(Boolean.TRUE.equals(input.get("compilationUnit")))return new String[]{text,"0"};
        String source = (String) input.getOrDefault("source", "");
        String name = (String) input.getOrDefault("part", "");
        if (!source.isEmpty() && !name.isEmpty()) {
            String signature=Objects.toString(input.get("partSignature"),"");
            String body=Objects.toString(input.get("partBody"),"");
            List<String> key=List.of(source,name,signature,body);
            if(!key.equals(lastPartKey)){
                locatePart(source,name,signature,body);
                lastPartKey=key;
            }
            if(partFrom>=0&&partTo>=partFrom)
                return new String[]{source.substring(0,partFrom)+text+source.substring(partTo),String.valueOf(partFrom)};
        }
        if (Pattern.compile("(?m)^\\s*(?:public\\s+)?(?:class|interface|enum|record)\\s+").matcher(text).find())
            return new String[]{text,"0"};
        StringBuilder imports = new StringBuilder();
        Matcher packageLine=Pattern.compile("(?m)^\\s*package\\s+[^;]+;").matcher(source);
        if(packageLine.find())imports.append(packageLine.group()).append('\n');
        Matcher matcher = Pattern.compile("(?m)^\\s*import\\s+[^;]+;").matcher(source);
        while (matcher.find()) imports.append(matcher.group()).append('\n');
        String before = imports + "\nclass __LucanetScript { void __edit() throws Exception {\n";
        return new String[]{before+text+"\n}}",String.valueOf(before.length())};
    }

    private void locatePart(String source,String name,String signature,String body)throws IOException {
            Analysis original = analyze(source,false,d->{},false);
            long[] range = {-1,-1};int[] best={-1};
            String enclosingName=simpleName(sourceName(source));
            String normalizedSignature=signature.replaceAll("\\s+","");
            new TreePathScanner<Void,Void>() {
                @Override public Void visitMethod(MethodTree node, Void unused) {
                    boolean constructor=node.getName().contentEquals("<init>") && enclosingName.equals(name);
                    if ((node.getName().contentEquals(name)||constructor) && node.getBody() != null) {
                        long from=original.trees.getSourcePositions().getStartPosition(original.unit,node.getBody())+1;
                        long to=original.trees.getSourcePositions().getEndPosition(original.unit,node.getBody())-1;
                        if(from<1||to<from)return null;
                        String parameters=node.getParameters().stream().map(Object::toString).collect(java.util.stream.Collectors.joining(","));
                        String key=(name+"("+parameters+")").replaceAll("\\s+","");
                        int score=normalizedSignature.contains(key)?100:0;
                        if(!body.isEmpty()&&source.substring((int)from,(int)to).contains(body))score+=50;
                        if(score>best[0]){range[0]=from;range[1]=to;best[0]=score;}
                    }
                    return null; // Never confuse a nested/local class's method with the active member.
                }
                @Override public Void visitVariable(VariableTree node, Void unused) {
                    if (node.getName().contentEquals(name) && node.getInitializer()!=null &&
                            getCurrentPath().getParentPath().getLeaf() instanceof ClassTree) {
                        range[0]=original.trees.getSourcePositions().getStartPosition(original.unit,node.getInitializer());
                        range[1]=original.trees.getSourcePositions().getEndPosition(original.unit,node.getInitializer());
                    }
                    return super.visitVariable(node,unused);
                }
            }.scan(original.unit,null);
            partFrom=(int)range[0];partTo=(int)range[1];
    }

    private Analysis analyze(String source, boolean attribution) throws IOException {
        return analyze(source,attribution,d->{},true);
    }
    private Analysis analyze(String source,boolean attribution,DiagnosticListener<JavaFileObject> diagnostics,boolean includeCompanions)throws IOException {
        String currentName=sourceName(source);
        List<JavaFileObject> units=new ArrayList<>();units.add(sourceFile(currentName,source));
        if(includeCompanions)for(Map.Entry<String,String> other:companionSources.entrySet())if(!other.getKey().equals(currentName))units.add(sourceFile(other.getKey(),other.getValue()));
        JavacTask task = (JavacTask) compiler.getTask(new StringWriter(),files,diagnostics,
                Arrays.asList("-proc:none","-Xlint:none","-implicit:none"),null,units);
        CompilationUnitTree unit = task.parse().iterator().next();
        if (attribution) { try { task.analyze(); } catch (RuntimeException incomplete) { /* Partially typed code is expected. */ } }
        return new Analysis(unit,Trees.instance(task),task.getElements(),task.getTypes());
    }
    private static JavaFileObject sourceFile(String name,String source) {
        return new SimpleJavaFileObject(URI.create("string:///"+name.replace('.','/')+".java"),JavaFileObject.Kind.SOURCE){
            @Override public CharSequence getCharContent(boolean ignore){return source;}
        };
    }
    private static String sourceName(String source) {
        String lexical=mask(source);
        Matcher type=TYPE_NAME.matcher(lexical);
        String name=type.find()?type.group(1):"__LucanetScript";
        Matcher pkg=PACKAGE_NAME.matcher(lexical);
        return pkg.find()?pkg.group(1)+"."+name:name;
    }
    private static final class Analysis {
        final CompilationUnitTree unit; final Trees trees; final Elements elements; final Types types;
        TypeMirror expected;
        List<TypeMirror> arguments;
        Analysis(CompilationUnitTree unit,Trees trees,Elements elements,Types types) {
            this.unit=unit;this.trees=trees;this.elements=elements;this.types=types;
        }
    }
    private static TypeMirror expectedType(Analysis a,TreePath path) {
        for(TreePath cursor=path;cursor!=null&&cursor.getParentPath()!=null;cursor=cursor.getParentPath()) {
            TreePath parent=cursor.getParentPath();Tree node=parent.getLeaf();
            if(node instanceof VariableTree)return a.trees.getTypeMirror(new TreePath(parent,((VariableTree)node).getType()));
            if(node instanceof AssignmentTree)return a.trees.getTypeMirror(new TreePath(parent,((AssignmentTree)node).getVariable()));
            if(node instanceof ReturnTree){Scope scope=scope(a,parent);return scope==null||scope.getEnclosingMethod()==null?null:scope.getEnclosingMethod().getReturnType();}
            if(node instanceof ExpressionStatementTree||node instanceof BlockTree||node instanceof MethodTree)break;
            // Only the marker invocation itself is transparent; don't borrow the expected type of an outer call.
            if(node instanceof MethodInvocationTree&&!((MethodInvocationTree)node).getMethodSelect().toString().endsWith(MARK))break;
        }
        return null;
    }
    private static void rankType(Map<String,String> row,Analysis a,TypeMirror type) {
        if(a.expected==null||a.expected.getKind()==TypeKind.ERROR||type==null||type.getKind()==TypeKind.ERROR)return;
        try{row.put("typeRank",a.types.isAssignable(type,a.expected)?"0":"2");}catch(IllegalArgumentException incomplete){}
    }
    private static boolean publicType(TypeElement type) {
        if (type==null) return false;
        for(Element e=type;e instanceof TypeElement;e=e.getEnclosingElement())
            if(!e.getModifiers().contains(Modifier.PUBLIC))return false;
        return true;
    }
    /** Direct new expressions must obey both type and constructor access at the actual caret scope. */
    private static boolean constructorAccessible(Analysis a,Scope scope,TypeElement owner,Element constructor,boolean anonymous) {
        if(scope==null||scope.getEnclosingClass()==null||constructor.getKind()!=ElementKind.CONSTRUCTOR)return false;
        // A protected constructor across packages permits super(...) or an anonymous subclass,
        // but never an ordinary new Base(...), even from within a subclass.
        if(constructor.getModifiers().contains(Modifier.PROTECTED)&&!anonymous&&
                !a.elements.getPackageOf(owner).equals(a.elements.getPackageOf(scope.getEnclosingClass())))return false;
        try{return a.trees.isAccessible(scope,owner)&&a.trees.isAccessible(scope,constructor,(DeclaredType)owner.asType());}
        catch(IllegalArgumentException incomplete){return false;}
    }
    private static boolean canInstantiate(Analysis a,Scope scope,TypeElement type) {
        if(type==null||type.getModifiers().contains(Modifier.ABSTRACT)||
                !(type.getKind()==ElementKind.CLASS||type.getKind().name().equals("RECORD")))return false;
        for(Element constructor:type.getEnclosedElements())if(constructorAccessible(a,scope,type,constructor,false))return true;
        return false;
    }
    private static void iconMetadata(Map<String,String> row,Analysis a,Element element) {
        String icon;
        switch(element.getKind()) {
            case ANNOTATION_TYPE:icon="annotation";break;
            case INTERFACE:icon="interface";break;
            case ENUM:icon="enum";break;
            case METHOD:icon=element.getModifiers().contains(Modifier.ABSTRACT)?"methodAbstract":"method";break;
            case CONSTRUCTOR:icon="method";break;
            case FIELD:case ENUM_CONSTANT:icon="field";break;
            case PARAMETER:icon="parameter";break;
            default:
                if(element instanceof TypeElement) {
                    boolean isAbstract=element.getModifiers().contains(Modifier.ABSTRACT),exception=false;
                    TypeElement throwable=a.elements.getTypeElement("java.lang.Throwable");
                    if(throwable!=null)try{exception=a.types.isSubtype(element.asType(),throwable.asType());}catch(IllegalArgumentException incomplete){}
                    icon=exception?(isAbstract?"abstractException":"exception"):element.getKind().name().equals("RECORD")?"record":isAbstract?"classAbstract":"class";
                }else icon="variable";
        }
        row.put("icon",icon);
        if(element.getKind()!=ElementKind.ENUM) {
            row.put("static",String.valueOf(element.getModifiers().contains(Modifier.STATIC)));
            row.put("final",String.valueOf(element.getModifiers().contains(Modifier.FINAL)));
        }
    }
    private void addMembers(Map<String,Map<String,String>> result,Analysis a,Scope scope,TypeMirror type,boolean staticOnly,String prefix) {
        if(type==null)return;
        if(type.getKind()==TypeKind.TYPEVAR)type=((TypeVariable)type).getUpperBound();
        if(!(type instanceof DeclaredType))return;
        DeclaredType declared=(DeclaredType)type;
        Element owner=declared.asElement();if(!(owner instanceof TypeElement))return;
        for(Element e:a.elements.getAllMembers((TypeElement)owner)) {
            if(staticOnly && !e.getModifiers().contains(Modifier.STATIC))continue;
            try { if(!a.trees.isAccessible(scope,e,declared))continue; } catch(IllegalArgumentException ignored){continue;}
            addElement(result,a,e,declared,prefix);
        }
    }
    private void addElement(Map<String,Map<String,String>> result,Analysis a,Element e,DeclaredType owner,String prefix) {
        String name=e.getSimpleName().toString();
        if(name.equals(MARK)||name.startsWith("__")||!matches(name,prefix))return;
        TypeMirror type=e.asType();
        if(owner!=null)try{type=a.types.asMemberOf(owner,e);}catch(IllegalArgumentException ignored){}
        if(e.getKind()==ElementKind.METHOD && type instanceof ExecutableType) {
            ExecutableType method=(ExecutableType)type; List<? extends VariableElement> params=((ExecutableElement)e).getParameters();
            StringJoiner signature=new StringJoiner(", ");
            for(int i=0;i<method.getParameterTypes().size();i++)
                signature.add(shortType(method.getParameterTypes().get(i).toString())+" "+params.get(i).getSimpleName());
            String label=name+"("+signature+") : "+shortType(method.getReturnType().toString());
            Map<String,String> value=add(result,name,label,name+"()","","method",name.length()+(params.isEmpty()?2:1));
            iconMetadata(value,a,e);
            value.put("detail",shortType(method.getReturnType().toString()));value.put("rank",e.getEnclosingElement().toString().equals("java.lang.Object")?"35":"10");
            value.put("parameters",signature.toString());value.put("qualified",e.getEnclosingElement().toString());
            value.put("parameterCount",String.valueOf(params.size()));value.put("varargs",String.valueOf(((ExecutableElement)e).isVarArgs()));
            rankType(value,a,method.getReturnType());
            rankArguments(value,a,method.getParameterTypes(),((ExecutableElement)e).isVarArgs());
        } else if(e instanceof VariableElement) {
            String kind=e.getKind()==ElementKind.FIELD||e.getKind()==ElementKind.ENUM_CONSTANT?"field":e.getKind()==ElementKind.PARAMETER?"parameter":"variable";
            Map<String,String> value=add(result,name,name+" : "+shortType(type.toString()),name,"",kind,name.length());
            iconMetadata(value,a,e);
            value.put("detail",shortType(type.toString()));value.put("rank",e.getKind()==ElementKind.FIELD?"8":"0");
            rankType(value,a,type);
        } else if(e instanceof TypeElement && !name.isEmpty()) {
            String qualified=((TypeElement)e).getQualifiedName().toString();
            Map<String,String> value=add(result,name,name+" — "+qualified,name,"",e.getKind().isInterface()?"interface":"class",name.length());
            iconMetadata(value,a,e);
            value.put("detail",packageName(qualified));value.put("rank",String.valueOf(classRank(qualified,a.unit)));
            typeParameters(value,(TypeElement)e);
        }
    }
    private static void typeParameters(Map<String,String> value,TypeElement type) {
        if(type.getTypeParameters().isEmpty())return;
        StringJoiner parameters=new StringJoiner(", ","<",">");
        for(TypeParameterElement parameter:type.getTypeParameters())parameters.add(parameter.getSimpleName().toString());
        // Presentation only: completion must not insert undeclared E/K/V into the user's code.
        value.put("typeParameters",parameters.toString());
    }
    static boolean matches(String name,String prefix) {
        if(name.regionMatches(true,0,prefix,0,prefix.length()))return true;
        String capitals=name.replaceAll("[^A-Z]","");
        return !prefix.isEmpty() && capitals.startsWith(prefix);
    }
    private static String shortType(String type) { return type.replaceAll("(?:[a-z_$][\\w$]*\\.)+",""); }
    private static Map<String,String> add(Map<String,Map<String,String>> result,String name,String label,String insert,String imported,String kind,int caret) {
        Map<String,String> value=new LinkedHashMap<>();
        value.put("name",name);value.put("label",label);value.put("insert",insert);value.put("import",imported);value.put("kind",kind);value.put("caret",String.valueOf(caret));
        Map<String,String> existing=result.putIfAbsent(label,value);return existing==null?value:existing;
    }
    private static String simpleName(String name){return name.substring(name.lastIndexOf('.')+1);}
    private static String packageName(String name){int dot=name.lastIndexOf('.');return dot<0?"":name.substring(0,dot);}
    private static int matchRank(String name,String prefix){return name.equals(prefix)?0:name.equalsIgnoreCase(prefix)?1:name.startsWith(prefix)?2:name.regionMatches(true,0,prefix,0,prefix.length())?3:4;}
    private int classRank(String qualified,CompilationUnitTree unit) {
        for(ImportTree imported:unit.getImports())if(!imported.isStatic()&&imported.getQualifiedIdentifier().toString().equals(qualified))return 3;
        if(customNames.contains(qualified))return 4;
        if(packageName(qualified).equals(String.valueOf(unit.getPackageName())))return 5;
        for(ImportTree imported:unit.getImports())if(!imported.isStatic()&&imported.getQualifiedIdentifier().toString().equals(packageName(qualified)+".*"))return 6;
        if(qualified.startsWith("java.lang."))return 12;
        if(qualified.startsWith("java."))return 20;
        if(qualified.startsWith("javax."))return 25;
        if(qualified.startsWith("lucanet.api.")||qualified.startsWith("ln.source.api."))return 28;
        return 45;
    }
    private TypeElement resolveType(Analysis a,String name) {
        name=name.replaceAll("<.*>","").trim();
        TypeElement direct=a.elements.getTypeElement(name);if(direct!=null)return direct;
        for(ImportTree i:a.unit.getImports())if(!i.isStatic()&&i.getQualifiedIdentifier().toString().endsWith("."+name)) {
            TypeElement type=a.elements.getTypeElement(i.getQualifiedIdentifier().toString());if(type!=null)return type;
        }
        String pkg=a.unit.getPackageName()==null?"":a.unit.getPackageName().toString();
        TypeElement same=a.elements.getTypeElement(pkg.isEmpty()?name:pkg+"."+name);if(same!=null)return same;
        LinkedHashMap<String,TypeElement> found=new LinkedHashMap<>();
        for(ImportTree i:a.unit.getImports())if(!i.isStatic()&&i.getQualifiedIdentifier().toString().endsWith(".*")) {
            TypeElement type=a.elements.getTypeElement(i.getQualifiedIdentifier().toString().replaceAll("\\*$",name));
            if(type!=null)found.put(type.getQualifiedName().toString(),type);
        }
        TypeElement lang=a.elements.getTypeElement("java.lang."+name);if(lang!=null)found.put(lang.getQualifiedName().toString(),lang);
        return found.size()==1?found.values().iterator().next():null;
    }
    private static List<TypeMirror> argumentTypes(Analysis a,TreePath path,List<? extends ExpressionTree> arguments) {
        List<TypeMirror> types=new ArrayList<>();for(ExpressionTree argument:arguments)types.add(a.trees.getTypeMirror(new TreePath(path,argument)));return types;
    }
    private static void rankArguments(Map<String,String> row,Analysis a,List<? extends TypeMirror> parameters,boolean varargs) {
        if(a.arguments==null)return;int score=0;
        for(int i=0;i<a.arguments.size();i++) {
            TypeMirror actual=a.arguments.get(i);if(actual==null||actual.getKind()==TypeKind.ERROR||actual.getKind()==TypeKind.NONE)continue;
            if(parameters.isEmpty()||i>=parameters.size()&&!varargs){score+=20;continue;}
            TypeMirror expected=parameters.get(Math.min(i,parameters.size()-1));
            if(expected.getKind()==TypeKind.TYPEVAR)expected=((TypeVariable)expected).getUpperBound();
            try {
                if(a.types.isSameType(actual,expected))continue;
                if(a.types.isAssignable(actual,expected)){score++;continue;}
                if(varargs&&i>=parameters.size()-1&&expected instanceof ArrayType&&a.types.isAssignable(actual,((ArrayType)expected).getComponentType())){score++;continue;}
                score+=10;
            }catch(IllegalArgumentException incomplete){}
        }
        row.put("argumentRank",String.valueOf(score));
    }
    private List<Map<String,String>> parameters(String text,int caret,String[] embedded)throws IOException {
        String lexical=mask(text);if(caret<text.length()&&text.charAt(caret)=='(')caret++;
        int open=-1,level=0,argument=0,angles=0;Deque<Integer> openings=new ArrayDeque<>();
        for(int i=0;i<Math.min(caret,lexical.length());i++) {
            char c=lexical.charAt(i);
            if(c=='('||c=='['||c=='{')openings.push(i);
            else if(c==')'||c==']'||c=='}'){if(!openings.isEmpty())openings.pop();}
        }
        for(int at:openings)if(lexical.charAt(at)=='('&&isCall(lexical,at)){open=at;break;}
        if(open<0)return Collections.emptyList();
        level=0;
        for(int i=open+1;i<Math.min(caret,lexical.length());i++) {
            char c=lexical.charAt(i);if(c=='('||c=='['||c=='{')level++;else if(c==')'||c==']'||c=='}')level--;
            else if(c=='<'&&i>0&&Character.isJavaIdentifierPart(lexical.charAt(i-1))&&Pattern.compile("^<[\\w$.?<> ,\\[\\]]*>\\s*(?:\\(|\\.)").matcher(lexical.substring(i)).find())angles++;
            else if(c=='>'&&angles>0)angles--;else if(c==','&&level==0&&angles==0)argument++;
        }
        int end=open;while(end>0&&Character.isWhitespace(lexical.charAt(end-1)))end--;
        int typeEnd=end;
        if(end>0&&lexical.charAt(end-1)=='>'){int depth=1;end--;while(end>0&&depth>0){char c=lexical.charAt(--end);if(c=='>')depth++;if(c=='<')depth--;}while(end>0&&Character.isWhitespace(lexical.charAt(end-1)))end--;}
        int start=end;while(start>0&&(Character.isJavaIdentifierPart(lexical.charAt(start-1))||lexical.charAt(start-1)=='.'))start--;
        String target=lexical.substring(start,end);if(target.isEmpty())return Collections.emptyList();
        boolean constructor=Pattern.compile("\\bnew\\s+$").matcher(lexical.substring(0,start)).find();
        Map<String,Map<String,String>> result=new LinkedHashMap<>();
        int base=Integer.parseInt(embedded[1]);
        if(constructor) {
            Analysis a=analyze(embedded[0],true);TypeElement owner=resolveType(a,target);
            if(owner==null)return Collections.emptyList();
            final int identifierEnd=base+typeEnd;
            TreePath[] invocation={null};
            new TreePathScanner<Void,Void>(){@Override public Void visitNewClass(NewClassTree n,Void u){
                if(a.trees.getSourcePositions().getEndPosition(a.unit,n.getIdentifier())==identifierEnd){a.arguments=argumentTypes(a,getCurrentPath(),n.getArguments());invocation[0]=getCurrentPath();}
                return super.visitNewClass(n,u);
            }}.scan(a.unit,null);
            Scope callScope=scope(a,invocation[0]);boolean anonymous=invocation[0]!=null&&((NewClassTree)invocation[0].getLeaf()).getClassBody()!=null;
            String name=owner.getSimpleName().toString();
            for(Element e:owner.getEnclosedElements())if(constructorAccessible(a,callScope,owner,e,anonymous)) {
                ExecutableElement method=(ExecutableElement)e;StringJoiner params=new StringJoiner(", ");
                for(VariableElement p:method.getParameters())params.add(shortType(p.asType().toString())+" "+p.getSimpleName());
                Map<String,String> row=add(result,name,name+"("+params+")",name,"","constructor",name.length());row.put("parameters",params.toString());
                iconMetadata(row,a,e);
                row.put("detail",packageName(owner.getQualifiedName().toString()));row.put("parameterCount",String.valueOf(method.getParameters().size()));row.put("varargs",String.valueOf(method.isVarArgs()));
                rankArguments(row,a,((ExecutableType)method.asType()).getParameterTypes(),method.isVarArgs());
            }
        }else {
            int nameStart=end;while(nameStart>0&&Character.isJavaIdentifierPart(lexical.charAt(nameStart-1)))nameStart--;
            String name=lexical.substring(nameStart,end);
            String source=embedded[0].substring(0,base+nameStart)+MARK+embedded[0].substring(base+end);
            Analysis a=analyze(source,true);TreePath path=marker(a);Scope scope=scope(a,path);
            if(path==null||scope==null)return Collections.emptyList();
            TreePath invocation=path.getParentPath();
            if(invocation!=null&&invocation.getLeaf() instanceof MethodInvocationTree)a.arguments=argumentTypes(a,invocation,((MethodInvocationTree)invocation.getLeaf()).getArguments());
            if(path.getLeaf() instanceof MemberSelectTree) {
                TreePath receiver=new TreePath(path,((MemberSelectTree)path.getLeaf()).getExpression());
                addMembers(result,a,scope,a.trees.getTypeMirror(receiver),a.trees.getElement(receiver) instanceof TypeElement,name);
            }else {
                if(scope.getEnclosingClass()!=null)addMembers(result,a,scope,scope.getEnclosingClass().asType(),scope.getEnclosingMethod()!=null&&scope.getEnclosingMethod().getModifiers().contains(Modifier.STATIC),name);
                for(Scope s=scope;s!=null;s=s.getEnclosingScope())for(Element e:s.getLocalElements())addElement(result,a,e,null,name);
            }
            result.values().removeIf(row->!row.get("kind").equals("method")||!row.get("name").equals(name));
        }
        final int active=argument;
        for(Map<String,String> row:result.values())row.put("activeParameter",String.valueOf(active));
        List<Map<String,String>> values=new ArrayList<>(result.values());
        values.sort(Comparator.comparingInt((Map<String,String> row)->Integer.parseInt(row.getOrDefault("argumentRank","0")))
                .thenComparingInt(row->Integer.parseInt(row.getOrDefault("parameterCount","99"))<=active&&!Boolean.parseBoolean(row.getOrDefault("varargs","false"))?1:0)
                .thenComparingInt(row->Integer.parseInt(row.getOrDefault("parameterCount","99"))).thenComparing(row->row.get("label")));
        return values;
    }
    private static boolean isCall(String text,int open) {
        int end=open;while(end>0&&Character.isWhitespace(text.charAt(end-1)))end--;
        if(end>0&&text.charAt(end-1)=='>')return true;
        int start=end;while(start>0&&Character.isJavaIdentifierPart(text.charAt(start-1)))start--;
        String name=text.substring(start,end);return !name.isEmpty()&&!Arrays.asList("if","while","for","switch","catch","synchronized","try").contains(name);
    }
    private static Scope scope(Analysis a,TreePath path){try{return path==null?null:a.trees.getScope(path);}catch(RuntimeException incomplete){return null;}}
    private static TreePath marker(Analysis a) {
        TreePath[] found={null};new TreePathScanner<Void,Void>(){
            @Override public Void visitIdentifier(IdentifierTree n,Void u){if(n.getName().contentEquals(MARK))found[0]=getCurrentPath();return super.visitIdentifier(n,u);}
            @Override public Void visitMemberSelect(MemberSelectTree n,Void u){if(n.getIdentifier().contentEquals(MARK))found[0]=getCurrentPath();return super.visitMemberSelect(n,u);}
        }.scan(a.unit,null);return found[0];
    }
    private void addPostfix(Map<String,Map<String,String>> result,Analysis a,Scope scope,TreePath receiver,String text,int base,int start,int end,String prefix,boolean isType)throws IOException {
        long position=a.trees.getSourcePositions().getStartPosition(a.unit,receiver.getLeaf());int from=(int)position-base;
        if(from<0||from>=start-1)return;
        String expression=text.substring(from,start-1);TypeMirror type=a.trees.getTypeMirror(receiver);
        if(type==null||type.getKind()==TypeKind.ERROR) {
            if(!expression.matches("[A-Z][\\w$]*")||!matches("new",prefix))return;
            index();SortedSet<String> names=classes.get(expression);if(names==null)return;
            for(String name:names) {
                TypeElement candidate=a.elements.getTypeElement(name);
                if(!canInstantiate(a,scope,candidate))continue;
                Map<String,String> row=add(result,"new","new "+expression+"() — "+name,"new "+expression+"()",name,"postfix",expression.length()+5);
                row.put("replaceStart",String.valueOf(from));row.put("replaceEnd",String.valueOf(end));row.put("typeName",expression);row.put("detail",name);row.put("rank",String.valueOf(classRank(name,a.unit)));
            }
            return;
        }
        TreePath call=receiver.getParentPath().getParentPath();
        boolean statement=call!=null&&call.getParentPath()!=null&&call.getParentPath().getLeaf() instanceof ExpressionStatementTree;
        int line=text.lastIndexOf('\n',from)+1;Matcher indentation=Pattern.compile("[ \\t]*").matcher(text.substring(line,from));indentation.find();String indent=indentation.group();
        Map<String,String> templates=new LinkedHashMap<>();
        if(isType) {
            Element e=a.trees.getElement(receiver);
            if(e instanceof TypeElement&&canInstantiate(a,scope,(TypeElement)e))
                templates.put("new","new "+expression+"(|)");
        }else {
            if(statement&&type.getKind()!=TypeKind.VOID){templates.put("sout","System.out.println("+expression+");|");templates.put("return","return "+expression+";|");}
            if(statement&&!type.getKind().isPrimitive()){templates.put("nn","if ("+expression+" != null) {\n"+indent+"    |\n"+indent+"}");templates.put("null","if ("+expression+" == null) {\n"+indent+"    |\n"+indent+"}");}
            if(type.getKind()==TypeKind.BOOLEAN||type.toString().equals("java.lang.Boolean")){if(statement)templates.put("if","if ("+expression+") {\n"+indent+"    |\n"+indent+"}");templates.put("not","!("+expression+")|");}
        }
        for(Map.Entry<String,String> template:templates.entrySet())if(matches(template.getKey(),prefix)) {
            String value=template.getValue();int cursor=value.indexOf('|');String insertion=value.replace("|","");
            Map<String,String> row=add(result,template.getKey(),template.getKey()+" — "+insertion.replace('\n',' '),insertion,"","postfix",cursor);
            row.put("replaceStart",String.valueOf(from));row.put("replaceEnd",String.valueOf(end));row.put("detail",insertion.replace('\n',' '));row.put("rank","-10");
            row.put("statement",String.valueOf(!template.getKey().equals("new")&&!template.getKey().equals("not")));
        }
    }
    private List<Map<String,String>> organize(Map<String,Object> input,String source)throws IOException {
        @SuppressWarnings("unchecked") List<String> names=(List<String>)input.getOrDefault("importNames",Collections.emptyList());
        @SuppressWarnings("unchecked") List<String> editable=(List<String>)input.getOrDefault("editableImports",names);
        TreeSet<String> next=new TreeSet<>(names);Analysis a=analyze(source,true);
        Map<String,List<String>> groups=new TreeMap<>();
        for(String name:editable) {
            if(name.endsWith(".*")||name.startsWith("static "))continue;
            TypeElement type=a.elements.getTypeElement(name);
            if(type==null||!(type.getEnclosingElement() instanceof PackageElement))continue;
            groups.computeIfAbsent(packageName(name),k->new ArrayList<>()).add(name);
        }
        for(Map.Entry<String,List<String>> group:groups.entrySet())if(group.getValue().size()>=2||next.contains(group.getKey()+".*")) {
            next.add(group.getKey()+".*");next.removeAll(group.getValue());
        }
        // Explicit imports continue to disambiguate classes exposed by multiple wildcard packages.
        Set<String> packages=new LinkedHashSet<>();packages.add("java.lang");
        String currentPackage=a.unit.getPackageName()==null?"":a.unit.getPackageName().toString();
        packages.add(currentPackage);
        for(String name:next)if(name.endsWith(".*")&&!name.startsWith("static "))packages.add(name.substring(0,name.length()-2));
        Map<String,String> referenced=new LinkedHashMap<>();
        for(String name:names)if(!name.endsWith(".*")&&!name.startsWith("static "))referenced.put(simpleName(name),name);
        new TreePathScanner<Void,Void>(){@Override public Void visitIdentifier(IdentifierTree n,Void u){
            Element e=a.trees.getElement(getCurrentPath());if(e instanceof TypeElement)referenced.put(n.getName().toString(),((TypeElement)e).getQualifiedName().toString());return super.visitIdentifier(n,u);
        }}.scan(a.unit,null);
        for(Map.Entry<String,String> entry:referenced.entrySet()) {
            Set<String> candidates=new HashSet<>();for(String pkg:packages){TypeElement t=a.elements.getTypeElement((pkg.isEmpty()?"":pkg+".")+entry.getKey());if(t!=null&&(publicType(t)||pkg.equals(currentPackage)))candidates.add(t.getQualifiedName().toString());}
            if(candidates.size()>1 && candidates.contains(entry.getValue()))next.add(entry.getValue());
        }
        List<Map<String,String>> values=new ArrayList<>();for(String name:next)values.add(Collections.singletonMap("import",name));return values;
    }
    static String mask(String text) {
        StringBuilder out=new StringBuilder(text);int state=0;
        for(int i=0;i<text.length();i++) {
            char c=text.charAt(i),next=i+1<text.length()?text.charAt(i+1):0;
            if(state==0) {
                if(c=='/'&&(next=='/'||next=='*')){state=next=='/'?1:2;out.setCharAt(i,' ');out.setCharAt(++i,' ');}
                else if(text.startsWith("\"\"\"",i)){state=5;for(int n=0;n<3;n++)out.setCharAt(i+n,' ');i+=2;}
                else if(c=='"'||c=='\''){state=c=='"'?3:4;out.setCharAt(i,' ');}
            }else {
                if(c!='\n'&&c!='\r')out.setCharAt(i,' ');
                if(state==1&&c=='\n')state=0;
                else if(state==2&&c=='*'&&next=='/'){out.setCharAt(++i,' ');state=0;}
                else if((state==3||state==4||state==5)&&c=='\\'&&i+1<text.length())out.setCharAt(++i,' ');
                else if(state==5&&text.startsWith("\"\"\"",i)){for(int n=0;n<3;n++)out.setCharAt(i+n,' ');i+=2;state=0;}
                else if(state==3&&c=='"'||state==4&&c=='\'')state=0;
            }
        }return out.toString();
    }
    private void index() throws IOException {
        if(indexed)return;
        // The running classloader supplies the classpath; no cache-directory/version guessing.
        for(String path:classpath.split(Pattern.quote(File.pathSeparator))) {
            if(!path.endsWith(".jar")||!Files.isRegularFile(Path.of(path)))continue;
            try(ZipFile jar=new ZipFile(path)) {
                Enumeration<? extends ZipEntry> entries=jar.entries();
                while(entries.hasMoreElements())addClass(entries.nextElement().getName());
            } catch(IOException unreadable){ /* An unavailable optional library must not disable JDK completion. */ }
        }
        for(Set<JavaFileManager.Location> locations:files.listLocationsForModules(StandardLocation.SYSTEM_MODULES)) {
            for(JavaFileManager.Location location:locations) {
                String module=files.inferModuleName(location);
                if(!module.startsWith("java."))continue;
                for(JavaFileObject file:files.list(location,"",EnumSet.of(JavaFileObject.Kind.CLASS),true))
                    addClass(files.inferBinaryName(location,file).replace('.','/')+".class");
            }
        }
        indexed=true;
    }
    private void addClass(String entry) {
        if(!entry.endsWith(".class")||entry.indexOf('$')>=0||entry.startsWith("META-INF/")||entry.indexOf('/')<0)return;
        String qualified=entry.substring(0,entry.length()-6).replace('/','.');
        if(qualified.startsWith("sun.")||qualified.startsWith("com.sun.")||qualified.contains(".internal."))return;
        String simple=qualified.substring(qualified.lastIndexOf('.')+1);
        if(simple.isEmpty()||!Character.isUpperCase(simple.charAt(0)))return;
        classes.computeIfAbsent(simple,k->new TreeSet<>()).add(qualified);
    }
    @Override public void close() throws IOException { if(files!=null)files.close(); }
}
