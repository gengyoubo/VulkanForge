package github.com.gengyoubo.vulkanforge.shaderpack;

import java.util.*;
import java.util.regex.*;

/** Conditional GLSL/properties preprocessing. Inactive directives must never affect render attachments. */
public final class ShaderPackPreprocessor {
    private record Macro(List<String> arguments,String body){ }
    private record Branch(boolean parent,boolean taken,boolean active,boolean otherwise){ }
    public record Result(String text,Map<String,String> macros){public Result{macros=Map.copyOf(macros);}}
    private interface Value {long get();}
    private static final Pattern DIRECTIVE=Pattern.compile("^\\s*#\\s*(\\w+)(.*)$");
    private static final Pattern DEFINE=Pattern.compile("^\\s*([A-Za-z_]\\w*)(\\([^)]*\\))?(.*)$");
    private static final Pattern TOKEN=Pattern.compile("\\s*([A-Za-z_]\\w*|0[xX][0-9a-fA-F]+[uUlL]*|[0-9]+[uUlL]*|\\|\\||&&|==|!=|<=|>=|<<|>>|[()?:,+*/%~!<>&^|\\-])");
    private ShaderPackPreprocessor(){ }
    public static Map<String,String> environment(){
        Map<String,String> result=new HashMap<>();result.put("MC_VERSION","12001");
        ShaderPackFormats.ENUMS.forEach((name,value)->result.put(name,Integer.toString(value)));
        String os=System.getProperty("os.name","").toLowerCase(Locale.ROOT);result.put(os.startsWith("windows")?"MC_OS_WINDOWS":os.contains("mac")?"MC_OS_MAC":"MC_OS_LINUX","1");return result;
    }
    public static String active(String source){return active(source,environment());}
    public static String active(String source,Map<String,String> predefined){
        return process(source,predefined).text();
    }
    public static Result process(String source,Map<String,String> predefined){
        Map<String,Macro> macros=new HashMap<>();predefined.forEach((key,value)->macros.put(key,new Macro(null,value)));
        Deque<Branch> branches=new ArrayDeque<>();StringBuilder output=new StringBuilder();boolean block=false;
        String[] lines=source.split("\\R",-1);
        for(int index=0;index<lines.length;index++){
            int first=index;String line=lines[index],logical=line;
            while(logical.endsWith("\\")&&index+1<lines.length)logical=logical.substring(0,logical.length()-1)+lines[++index];
            StringBuilder clean=new StringBuilder();boolean quoted=false;char quote=0;
            for(int p=0;p<logical.length();p++){
                char c=logical.charAt(p),next=p+1<logical.length()?logical.charAt(p+1):0;
                if(block){if(c=='*'&&next=='/'){block=false;p++;clean.append(' ');}continue;}
                if(!quoted&&c=='/'&&next=='*'){block=true;p++;clean.append(' ');continue;}
                if(!quoted&&c=='/'&&next=='/')break;
                if((c=='\"'||c=='\'')&&(p==0||logical.charAt(p-1)!='\\')){if(!quoted){quoted=true;quote=c;}else if(c==quote)quoted=false;}
                clean.append(c);
            }
            Matcher directive=DIRECTIVE.matcher(clean);boolean enabled=branches.isEmpty()||branches.peek().active();boolean control=false;
            try{
                if(directive.matches()){
                    String command=directive.group(1),argument=directive.group(2).strip();
                    switch(command){
                        case "if","ifdef","ifndef"->{boolean test=enabled&&(command.equals("if")?evaluate(argument,macros):command.equals("ifdef")==macros.containsKey(argument));branches.push(new Branch(enabled,test,test,false));control=true;}
                        case "elif"->{if(branches.isEmpty())throw new ShaderPackException("#elif without #if");Branch previous=branches.pop();if(previous.otherwise())throw new ShaderPackException("#elif after #else");boolean test=previous.parent()&&!previous.taken()&&evaluate(argument,macros);branches.push(new Branch(previous.parent(),previous.taken()||test,test,false));control=true;}
                        case "else"->{if(branches.isEmpty())throw new ShaderPackException("#else without #if");Branch previous=branches.pop();if(previous.otherwise())throw new ShaderPackException("Duplicate #else");boolean test=previous.parent()&&!previous.taken();branches.push(new Branch(previous.parent(),true,test,true));control=true;}
                        case "endif"->{if(branches.isEmpty())throw new ShaderPackException("#endif without #if");branches.pop();control=true;}
                        case "define"->{if(enabled){Matcher definition=DEFINE.matcher(argument);if(!definition.matches())throw new ShaderPackException("Malformed #define");List<String> parameters=definition.group(2)==null?null:definition.group(2).length()==2?List.of():Arrays.stream(definition.group(2).substring(1,definition.group(2).length()-1).split(",")).map(String::strip).toList();macros.put(definition.group(1),new Macro(parameters,definition.group(3).strip()));}}
                        case "undef"->{if(enabled)macros.remove(argument);}
                        case "version"->{if(enabled){Matcher version=Pattern.compile("^(\\d+)").matcher(argument);if(version.find())macros.put("__VERSION__",new Macro(null,version.group(1)));}}
                        default->{ }
                    }
                }
            }catch(RuntimeException error){throw new ShaderPackException("Preprocessor line "+(first+1)+": "+error.getMessage(),error);}
            for(int row=first;row<=index;row++)output.append(enabled&&!control?lines[row]:"").append('\n');
        }
        if(!branches.isEmpty())throw new ShaderPackException("Unterminated shader #if");
        Map<String,String> exported=new HashMap<>();macros.forEach((name,macro)->{if(macro.arguments()==null)exported.put(name,macro.body());});return new Result(output.toString(),exported);
    }
    /** Expands object/function macros in property values after resolving conditional directives. */
    public static String expandValue(String source,Map<String,String> predefined){
        Map<String,Macro> macros=new HashMap<>();predefined.forEach((key,value)->macros.put(key,new Macro(null,value)));
        Matcher identifiers=Pattern.compile("[A-Za-z_]\\w*").matcher(source);StringBuffer result=new StringBuffer();
        while(identifiers.find()){String name=identifiers.group(),value=predefined.get(name);if(value==null||value.isBlank())value=name;else{Set<String> seen=new HashSet<>();while(value.matches("[A-Za-z_]\\w*")&&predefined.containsKey(value)&&seen.add(value)){String next=predefined.get(value);if(next.isBlank())break;value=next;}}identifiers.appendReplacement(result,Matcher.quoteReplacement(value));}identifiers.appendTail(result);return result.toString();
    }
    public static boolean condition(String expression,Map<String,String> predefined){Map<String,Macro> macros=new HashMap<>();predefined.forEach((key,value)->macros.put(key,new Macro(null,value)));return evaluate(expression,macros);}
    private static boolean evaluate(String expression,Map<String,Macro> macros){return new Expression(expand(tokens(expression),macros,new HashSet<>(),0)).parse().get()!=0;}
    private static List<String> tokens(String text){
        List<String> result=new ArrayList<>();Matcher matcher=TOKEN.matcher(text);int at=0;
        while(at<text.length()){matcher.region(at,text.length());if(!matcher.lookingAt()){if(text.substring(at).isBlank())break;throw new ShaderPackException("Unsupported preprocessor expression near "+text.substring(at));}result.add(matcher.group(1));at=matcher.end();}
        return result;
    }
    private static List<String> expand(List<String> input,Map<String,Macro> macros,Set<String> disabled,int depth){
        if(depth>64)throw new ShaderPackException("Preprocessor macro expansion exceeds 64 levels");List<String> output=new ArrayList<>();
        for(int i=0;i<input.size();i++){
            String token=input.get(i);
            if(token.equals("defined")){
                boolean parent=i+1<input.size()&&input.get(i+1).equals("(");if(parent)i++;
                if(++i>=input.size()||!input.get(i).matches("[A-Za-z_]\\w*"))throw new ShaderPackException("Malformed defined operand");output.add(macros.containsKey(input.get(i))?"1":"0");
                if(parent&&(++i>=input.size()||!input.get(i).equals(")")))throw new ShaderPackException("Missing ')' after defined");continue;
            }
            Macro macro=macros.get(token);
            if(macro==null||disabled.contains(token)){output.add(token);continue;}
            List<String> replacement=tokens(macro.body());
            if(macro.arguments()!=null){
                if(i+1>=input.size()||!input.get(i+1).equals("(")){output.add(token);continue;}
                List<List<String>> arguments=new ArrayList<>();List<String> current=new ArrayList<>();int nesting=0;boolean closed=false;i++;
                while(++i<input.size()){
                    String part=input.get(i);
                    if(part.equals(")")&&nesting==0){if(!current.isEmpty()||!arguments.isEmpty()||!macro.arguments().isEmpty())arguments.add(current);closed=true;break;}
                    if(part.equals(",")&&nesting==0){arguments.add(current);current=new ArrayList<>();continue;}
                    if(part.equals("("))nesting++;else if(part.equals(")"))nesting--;current.add(part);
                }
                if(!closed||arguments.size()!=macro.arguments().size())throw new ShaderPackException("Incorrect arguments to macro "+token);
                List<String> substituted=new ArrayList<>();for(String part:replacement){int parameter=macro.arguments().indexOf(part);if(parameter<0)substituted.add(part);else substituted.addAll(arguments.get(parameter));}replacement=substituted;
            }
            Set<String> nested=new HashSet<>(disabled);nested.add(token);output.addAll(expand(replacement,macros,nested,depth+1));
            if(output.size()>65536)throw new ShaderPackException("Preprocessor expression exceeds 65536 tokens");
        }return output;
    }
    private static final class Expression {
        private static final List<List<String>> OPERATORS=List.of(List.of("||"),List.of("&&"),List.of("|"),List.of("^"),List.of("&"),List.of("==","!="),List.of("<",">","<=",">="),List.of("<<",">>"),List.of("+","-"),List.of("*","/","%"));
        private final List<String> tokens;private int at;
        Expression(List<String> tokens){this.tokens=tokens;}
        Value parse(){Value result=conditional();if(at!=tokens.size())throw new ShaderPackException("Unexpected preprocessor token "+tokens.get(at));return result;}
        private boolean take(String token){if(at<tokens.size()&&tokens.get(at).equals(token)){at++;return true;}return false;}
        private void expect(String token){if(!take(token))throw new ShaderPackException("Expected '"+token+"' in preprocessor expression");}
        private Value conditional(){Value condition=binary(0);if(!take("?"))return condition;Value yes=conditional();expect(":");Value no=conditional();return ()->condition.get()!=0?yes.get():no.get();}
        private Value binary(int precedence){
            if(precedence==OPERATORS.size())return unary();Value result=binary(precedence+1);
            while(at<tokens.size()&&OPERATORS.get(precedence).contains(tokens.get(at))){String op=tokens.get(at++);Value left=result,right=binary(precedence+1);result=operation(op,left,right);}return result;
        }
        private Value unary(){
            if(take("!")){Value value=unary();return ()->value.get()==0?1:0;}if(take("~")){Value value=unary();return ()->~value.get();}if(take("-")){Value value=unary();return ()->-value.get();}if(take("+"))return unary();
            if(take("(")){Value value=conditional();expect(")");return value;}if(at>=tokens.size())throw new ShaderPackException("Missing preprocessor operand");String token=tokens.get(at++);
            if(token.matches("[A-Za-z_]\\w*"))return ()->0;String numeric=token.replaceFirst("[uUlL]+$","");
            long value=numeric.startsWith("0x")||numeric.startsWith("0X")?Long.parseUnsignedLong(numeric.substring(2),16):numeric.length()>1&&numeric.startsWith("0")?Long.parseUnsignedLong(numeric,8):Long.parseUnsignedLong(numeric);return ()->value;
        }
        private static Value operation(String op,Value left,Value right){return switch(op){
            case "||"->()->left.get()!=0||right.get()!=0?1:0;case "&&"->()->left.get()!=0&&right.get()!=0?1:0;
            case "|"->()->left.get()|right.get();case "^"->()->left.get()^right.get();case "&"->()->left.get()&right.get();
            case "=="->()->left.get()==right.get()?1:0;case "!="->()->left.get()!=right.get()?1:0;case "<"->()->left.get()<right.get()?1:0;case ">"->()->left.get()>right.get()?1:0;case "<="->()->left.get()<=right.get()?1:0;case ">="->()->left.get()>=right.get()?1:0;
            case "<<"->()->left.get()<<right.get();case ">>"->()->left.get()>>right.get();case "+"->()->left.get()+right.get();case "-"->()->left.get()-right.get();case "*"->()->left.get()*right.get();case "/"->()->left.get()/right.get();case "%"->()->left.get()%right.get();default->throw new ShaderPackException("Unknown preprocessor operator "+op);
        };}
    }
}
