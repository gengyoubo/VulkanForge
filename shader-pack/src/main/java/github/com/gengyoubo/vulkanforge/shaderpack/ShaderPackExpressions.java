package github.com.gengyoubo.vulkanforge.shaderpack;

import java.util.*;
import java.util.function.DoubleBinaryOperator;
import java.util.function.DoubleUnaryOperator;
import java.util.regex.*;

/** Compiled, bounded arithmetic for shader-pack properties. No scripting engine or executable pack code. */
public final class ShaderPackExpressions {
    private interface Node { double[] value(Frame frame); }
    private record Definition(String type,boolean uniform,Node expression) { }
    private record Smooth(double value,long frame) { }
    private final Map<String,Definition> definitions=new LinkedHashMap<>();
    private final Map<String,Smooth> smoothing=new HashMap<>();
    private final SplittableRandom random=new SplittableRandom();
    private long serial;
    public ShaderPackExpressions(Map<String,String> properties){
        for(var entry:new TreeMap<>(properties).entrySet()){
            String[] key=entry.getKey().split("\\.",3);if(key.length!=3||!Set.of("uniform","variable").contains(key[0]))continue;
            if(!Set.of("bool","int","float","vec2","vec3","vec4","ivec2","ivec3","ivec4").contains(key[1]))throw new ShaderPackException("Unsupported expression type: "+entry.getKey());
            if(definitions.containsKey(key[2]))throw new ShaderPackException("Duplicate expression name: "+key[2]);
            try{definitions.put(key[2],new Definition(key[1],key[0].equals("uniform"),new Parser(entry.getValue(),key[2]).parse()));}
            catch(ShaderPackException error){throw new ShaderPackException(entry.getKey()+": "+error.getMessage(),error);}
        }
    }
    /** Evaluate once per rendered frame; dependent variables are memoized and smooth histories are pack-local. */
    public Map<String,float[]> evaluate(Map<String,float[]> input,float seconds){
        Frame frame=new Frame(input,Math.max(0,Math.min(1,seconds)),++serial);Map<String,float[]> output=new LinkedHashMap<>();
        definitions.forEach((name,definition)->{if(definition.uniform()){double[] raw=frame.resolve(name);float[] values=new float[raw.length];for(int i=0;i<raw.length;i++)values[i]=(float)raw[i];output.put(name,values);}});return output;
    }
    public Set<String> uniforms(){Set<String> names=new LinkedHashSet<>();definitions.forEach((name,value)->{if(value.uniform())names.add(name);});return Set.copyOf(names);}
    private final class Frame {
        final Map<String,float[]> input;final Map<String,double[]> resolved=new HashMap<>();final Map<String,Double> randoms=new HashMap<>();final Set<String> visiting=new LinkedHashSet<>();final double dt;final long id;
        Frame(Map<String,float[]> input,double dt,long id){this.input=input;this.dt=dt;this.id=id;}
        double[] resolve(String name){
            double[] cached=resolved.get(name);if(cached!=null)return cached;
            Definition definition=definitions.get(name);
            if(definition==null){float[] values=input.get(name);if(values==null)throw new ShaderPackException("Unknown expression input: "+name);double[] result=new double[values.length];for(int i=0;i<result.length;i++)result[i]=values[i];return result;}
            if(!visiting.add(name))throw new ShaderPackException("Uniform expression cycle: "+visiting+" -> "+name);
            try{double[] value=definition.expression().value(this);int size=definition.type().endsWith("2")?2:definition.type().endsWith("3")?3:definition.type().endsWith("4")?4:1;if(value.length!=size)throw new ShaderPackException("Expression "+name+" expects "+size+" components, got "+value.length);value=value.clone();for(int i=0;i<value.length;i++)if(definition.type().equals("bool"))value[i]=truth(value[i])?1:0;else if(definition.type().equals("int")||definition.type().startsWith("ivec"))value[i]=(int)value[i];resolved.put(name,value);return value;}finally{visiting.remove(name);}
        }
    }
    private static double scalar(double[] value){if(value.length!=1)throw new ShaderPackException("Expected a scalar expression");return value[0];}
    private static double[] number(double value){return new double[]{value};}
    private static boolean truth(double value){return value!=0&&!Double.isNaN(value);}
    private static double[] unary(double[] value,DoubleUnaryOperator operation){double[] result=new double[value.length];for(int i=0;i<result.length;i++)result[i]=operation.applyAsDouble(value[i]);return result;}
    private static double[] binary(double[] a,double[] b,DoubleBinaryOperator operation){int size=Math.max(a.length,b.length);if(a.length!=1&&a.length!=size||b.length!=1&&b.length!=size)throw new ShaderPackException("Expression vector dimensions differ");double[] result=new double[size];for(int i=0;i<size;i++)result[i]=operation.applyAsDouble(a[a.length==1?0:i],b[b.length==1?0:i]);return result;}
    private Node function(String name,List<Node> args,String site){
        int count=args.size();
        if(name.equals("random")){
            if(count!=0&&count!=2)throw new ShaderPackException("random expects no arguments or min, max");
            return frame->{double min=count==0?0:scalar(args.get(0).value(frame)),max=count==0?1:scalar(args.get(1).value(frame));if(!Double.isFinite(min)||!Double.isFinite(max)||min>=max)throw new ShaderPackException("random requires finite min < max");double unit=frame.randoms.computeIfAbsent(site,unused->random.nextDouble());return number(Math.min(Math.nextDown((float)max),min+unit*(max-min)));};
        }
        if(name.equals("if")){if(count<3||count%2!=1)throw new ShaderPackException("if needs condition/value pairs and a default");return frame->{for(int i=0;i<count-1;i+=2)if(truth(scalar(args.get(i).value(frame))))return args.get(i+1).value(frame);return args.get(count-1).value(frame);};}
        if(name.equals("smooth")){
            if(count<2||count>4)throw new ShaderPackException("smooth needs id, value [, rise, fall]");
            return frame->{double id=scalar(args.get(0).value(frame)),target=scalar(args.get(1).value(frame));String key=site+":"+id;Smooth previous=smoothing.get(key);if(previous!=null&&previous.frame()==frame.id)return number(previous.value());double rise=count>2?scalar(args.get(2).value(frame)):1,fall=count>3?scalar(args.get(3).value(frame)):rise;double fade=previous!=null&&target<previous.value()?fall:rise,value=previous==null||fade<=0?target:previous.value()+(target-previous.value())*(-Math.expm1(-Math.log(2)*frame.dt/fade));smoothing.put(key,new Smooth(value,frame.id));return number(value);};
        }
        if(Set.of("vec2","vec3","vec4","ivec2","ivec3","ivec4").contains(name)){int size=name.charAt(name.length()-1)-'0';if(count<1)throw new ShaderPackException("Empty vector constructor");return frame->{List<Double> components=new ArrayList<>();for(Node arg:args)for(double value:arg.value(frame))components.add(value);double[] result=new double[size];if(components.size()!=1&&components.size()!=size)throw new ShaderPackException("Invalid "+name+" constructor");for(int i=0;i<size;i++)result[i]=components.get(components.size()==1?0:i);return result;};}
        if(name.equals("in")){if(count<2)throw new ShaderPackException("in needs candidates");return frame->{double value=scalar(args.get(0).value(frame));for(int i=1;i<count;i++)if(value==scalar(args.get(i).value(frame)))return number(1);return number(0);};}
        if(name.equals("min")||name.equals("max")){if(count<2)throw new ShaderPackException(name+" needs at least two values");return frame->{double[] value=args.get(0).value(frame);for(int i=1;i<count;i++)value=binary(value,args.get(i).value(frame),name.equals("min")?Math::min:Math::max);return value;};}
        Map<String,DoubleUnaryOperator> unary=Map.ofEntries(Map.entry("abs",Math::abs),Map.entry("sqrt",Math::sqrt),Map.entry("sin",Math::sin),Map.entry("cos",Math::cos),Map.entry("tan",Math::tan),Map.entry("asin",Math::asin),Map.entry("acos",Math::acos),Map.entry("atan",Math::atan),Map.entry("floor",Math::floor),Map.entry("ceil",Math::ceil),Map.entry("round",v->Math.floor(v+.5)),Map.entry("exp",Math::exp),Map.entry("log",Math::log),Map.entry("sign",Math::signum),Map.entry("frac",v->v-Math.floor(v)),Map.entry("fract",v->v-Math.floor(v)),Map.entry("radians",Math::toRadians),Map.entry("degrees",Math::toDegrees));
        if(unary.containsKey(name)){require(count,1,name);return frame->unary(args.get(0).value(frame),unary.get(name));}
        if(name.equals("pow")||name.equals("atan2")){require(count,2,name);return frame->binary(args.get(0).value(frame),args.get(1).value(frame),name.equals("pow")?Math::pow:Math::atan2);}
        if(name.equals("fmod")){require(count,2,name);return frame->binary(args.get(0).value(frame),args.get(1).value(frame),(x,y)->x-y*Math.floor(x/y));}
        if(name.equals("clamp")){require(count,3,name);return frame->binary(binary(args.get(0).value(frame),args.get(1).value(frame),Math::max),args.get(2).value(frame),Math::min);}
        if(name.equals("mix")){require(count,3,name);return frame->{double[] a=args.get(0).value(frame),b=args.get(1).value(frame),t=args.get(2).value(frame);return binary(a,binary(binary(b,a,(x,y)->x-y),t,(x,y)->x*y),(x,y)->x+y);};}
        if(name.equals("length")||name.equals("normalize")){require(count,1,name);return frame->{double[] vector=args.get(0).value(frame);double sum=0;for(double value:vector)sum+=value*value;double length=Math.sqrt(sum);return name.equals("length")?number(length):unary(vector,v->length==0?0:v/length);};}
        throw new ShaderPackException("Unsupported expression function: "+name);
    }
    private static void require(int count,int expected,String name){if(count!=expected)throw new ShaderPackException(name+" expects "+expected+" arguments");}
    private final class Parser {
        private static final Pattern TOKEN=Pattern.compile("\\s*(?:(\\d+(?:\\.\\d*)?(?:[eE][+-]?\\d+)?|\\.\\d+(?:[eE][+-]?\\d+)?)|([A-Za-z_]\\w*(?:\\.[0-3]\\.[0-3])?)|(\\|\\||&&|==|!=|<=|>=|[+*/%<>()!,?:.\\-]))");
        final List<String> tokens=new ArrayList<>();final String owner;int at,calls,depth;
        Parser(String text,String owner){this.owner=owner;if(text.length()>65536)throw new ShaderPackException("Expression too long");Matcher matcher=TOKEN.matcher(text);int end=0;while(matcher.find()){if(!text.substring(end,matcher.start()).isBlank())throw new ShaderPackException("Invalid expression near "+text.substring(end));tokens.add(matcher.group(1)!=null?matcher.group(1):matcher.group(2)!=null?matcher.group(2):matcher.group(3));end=matcher.end();}if(!text.substring(end).isBlank()||tokens.size()>4096)throw new ShaderPackException("Invalid/oversized expression");}
        Node parse(){Node result=conditional();if(at!=tokens.size())throw new ShaderPackException("Unexpected expression token: "+tokens.get(at));return result;}
        boolean take(String token){if(at<tokens.size()&&tokens.get(at).equals(token)){at++;return true;}return false;}
        void expect(String token){if(!take(token))throw new ShaderPackException("Expected "+token+" in expression");}
        Node conditional(){Node condition=binary(0);if(!take("?"))return condition;Node yes=conditional();expect(":");Node no=conditional();return frame->truth(scalar(condition.value(frame)))?yes.value(frame):no.value(frame);}
        final List<Set<String>> levels=List.of(Set.of("||"),Set.of("&&"),Set.of("==","!="),Set.of("<",">","<=",">="),Set.of("+","-"),Set.of("*","/","%"));
        Node binary(int level){if(level==levels.size())return primary();Node left=binary(level+1);while(at<tokens.size()&&levels.get(level).contains(tokens.get(at))){String op=tokens.get(at++);Node a=left,b=binary(level+1);left=frame->{double[] x=a.value(frame);if(op.equals("&&")&&!truth(scalar(x)))return number(0);if(op.equals("||")&&truth(scalar(x)))return number(1);double[] y=b.value(frame);return ShaderPackExpressions.binary(x,y,switch(op){case "+"->(u,v)->u+v;case "-"->(u,v)->u-v;case "*"->(u,v)->u*v;case "/"->(u,v)->u/v;case "%"->(u,v)->u%v;case "<"->(u,v)->u<v?1:0;case ">"->(u,v)->u>v?1:0;case "<="->(u,v)->u<=v?1:0;case ">="->(u,v)->u>=v?1:0;case "=="->(u,v)->u==v?1:0;case "!="->(u,v)->u!=v?1:0;case "&&"->(u,v)->truth(u)&&truth(v)?1:0;case "||"->(u,v)->truth(u)||truth(v)?1:0;default->throw new AssertionError(op);});};}return left;}
        Node primary(){if(++depth>128)throw new ShaderPackException("Expression nesting exceeds 128");try{return atom();}finally{depth--;}}
        Node atom(){if(take("!")){Node node=primary();return frame->number(truth(scalar(node.value(frame)))?0:1);}if(take("-")){Node node=primary();return frame->unary(node.value(frame),v->-v);}if(take("+"))return primary();Node node;if(take("(")){node=conditional();expect(")");}else{if(at>=tokens.size())throw new ShaderPackException("Incomplete expression");String value=tokens.get(at++);if(Character.isDigit(value.charAt(0))||value.charAt(0)=='.'){double literal=Double.parseDouble(value);node=frame->number(literal);}else if(value.equals("true")||value.equals("false"))node=frame->number(value.equals("true")?1:0);else if(value.equals("pi"))node=frame->number(Math.PI);else if(take("(")){List<Node> args=new ArrayList<>();if(!take(")")){do{args.add(conditional());}while(take(","));expect(")");}node=function(value,args,owner+":"+(calls++));}else if(value.matches("[A-Za-z_]\\w*\\.[0-3]\\.[0-3]")){String[] parts=value.split("\\.");int column=Integer.parseInt(parts[1]),row=Integer.parseInt(parts[2]);node=frame->{double[] matrix=frame.resolve(parts[0]);int size=(int)Math.sqrt(matrix.length);if(size*size!=matrix.length||size<2||column>=size||row>=size)throw new ShaderPackException("Invalid matrix component: "+value);return number(matrix[column*size+row]);};}else node=frame->frame.resolve(value);}
            while(take(".")){if(at>=tokens.size())throw new ShaderPackException("Missing vector swizzle");String swizzle=tokens.get(at++);if(!swizzle.matches("[xyzwrgba]{1,4}"))throw new ShaderPackException("Invalid vector swizzle: "+swizzle);Node input=node;node=frame->{double[] source=input.value(frame),result=new double[swizzle.length()];for(int i=0;i<result.length;i++){int index="xyzw".indexOf(swizzle.charAt(i));if(index<0)index="rgba".indexOf(swizzle.charAt(i));if(index>=source.length)throw new ShaderPackException("Vector swizzle exceeds input size: "+swizzle);result[i]=source[index];}return result;};}return node;}
    }
}
