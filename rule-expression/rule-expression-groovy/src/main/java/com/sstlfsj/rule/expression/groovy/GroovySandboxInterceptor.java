package com.sstlfsj.rule.expression.groovy;

import groovy.lang.Script;
import org.kohsuke.groovy.sandbox.GroovyValueFilter;

import java.util.Collection;
import java.util.Map;
import java.util.Set;

/**
 * Groovy 运行期沙箱过滤器:deny-by-default 白名单,EXPRESSION_SCRIPT 的安全闸。
 *
 * <p>由 {@code SandboxTransformer} 在编译期把脚本里的每一次方法调用/构造/静态调用/属性访问改写为
 * 经本拦截器转发;本拦截器只放行"决策规则需要的安全操作",其余一律抛 {@link SecurityException}:
 * <ul>
 *   <li>方法调用:仅当接收者是白名单值类型(String/Number/Boolean/Character/Map/Collection)且方法不在逃逸名单时放行;</li>
 *   <li>静态调用、构造器:一律禁(切断 {@code System.exit}/{@code Runtime}/{@code Class.forName}/{@code new File} 等);</li>
 *   <li>属性 {@code class}/{@code metaClass}:禁(切断反射与 MOP 逃逸);字段(.@)、所有写操作:禁。</li>
 * </ul>
 * 即便脚本设法拿到危险对象,因其类型不在接收者白名单,也无法对其调用任何方法。
 * 无状态、线程安全:单实例在每次 evaluate 时按线程 register/unregister。
 */
final class GroovySandboxInterceptor extends GroovyValueFilter {

    /** 允许作为方法调用/属性读取接收者的值类型(deny-by-default 白名单)。 */
    private static boolean allowedReceiver(Object receiver) {
        return receiver == null
                || receiver instanceof CharSequence   // String / GString
                || receiver instanceof Number          // Integer/Long/Double/BigDecimal/BigInteger/Float/...
                || receiver instanceof Boolean
                || receiver instanceof Character
                || receiver instanceof Map             // metrics/payload/subject 及嵌套 Map
                || receiver instanceof Collection;      // List/Set
    }

    /** 即便接收者类型合法也禁止的方法名(反射/MOP/命令执行/线程等逃逸口)。 */
    private static final Set<String> DENIED_METHODS = Set.of(
            "getClass", "getMetaClass", "setMetaClass", "invokeMethod", "getProperty", "setProperty",
            "execute", "wait", "notify", "notifyAll", "sleep", "finalize", "getClassLoader");

    /** 禁止读取的属性名(经其可跳到反射/类加载)。 */
    private static final Set<String> DENIED_PROPERTIES = Set.of("class", "metaClass", "classLoader");

    /** 顶层绑定变量名:脚本对自由变量 metrics/payload/subject/now 的引用经 Script.getProperty 解析,仅放行这四个。 */
    private static final Set<String> BINDING_KEYS = Set.of("metrics", "payload", "subject", "now");

    @Override
    public Object onMethodCall(Invoker invoker, Object receiver, String method, Object... args) throws Throwable {
        if (!allowedReceiver(receiver) || DENIED_METHODS.contains(method)) {
            throw new SecurityException("Groovy 沙箱拒绝方法调用: " + receiverType(receiver) + "." + method);
        }
        return super.onMethodCall(invoker, receiver, method, args);
    }

    @Override
    public Object onStaticCall(Invoker invoker, Class receiver, String method, Object... args) throws Throwable {
        // 静态调用一律禁:System.exit / Runtime.getRuntime / Class.forName / Eval.me / Thread.start ...
        throw new SecurityException("Groovy 沙箱拒绝静态调用: " + receiver.getName() + "." + method);
    }

    @Override
    public Object onNewInstance(Invoker invoker, Class receiver, Object... args) throws Throwable {
        // 构造器一律禁:new File / new ProcessBuilder / new Socket ...
        throw new SecurityException("Groovy 沙箱拒绝构造对象: new " + receiver.getName());
    }

    @Override
    public Object onGetProperty(Invoker invoker, Object receiver, String property) throws Throwable {
        if (DENIED_PROPERTIES.contains(property)) {
            throw new SecurityException("Groovy 沙箱拒绝属性读取: " + receiverType(receiver) + "." + property);
        }
        // 顶层绑定变量经 Script.getProperty 解析:仅放行 metrics/payload/subject/now,挡掉 binding/此类其它成员
        if (receiver instanceof Script) {
            if (BINDING_KEYS.contains(property)) {
                return super.onGetProperty(invoker, receiver, property);
            }
            throw new SecurityException("Groovy 沙箱拒绝脚本成员读取: " + property);
        }
        if (!allowedReceiver(receiver)) {
            throw new SecurityException("Groovy 沙箱拒绝属性读取: " + receiverType(receiver) + "." + property);
        }
        return super.onGetProperty(invoker, receiver, property);
    }

    @Override
    public Object onGetArray(Invoker invoker, Object receiver, Object index) throws Throwable {
        if (!allowedReceiver(receiver)) {
            throw new SecurityException("Groovy 沙箱拒绝下标访问: " + receiverType(receiver));
        }
        return super.onGetArray(invoker, receiver, index);
    }

    @Override
    public Object onGetAttribute(Invoker invoker, Object receiver, String attribute) throws Throwable {
        // 字段直接访问(.@field)一律禁
        throw new SecurityException("Groovy 沙箱拒绝字段访问: ." + attribute);
    }

    @Override
    public Object onSetProperty(Invoker invoker, Object receiver, String property, Object value) throws Throwable {
        throw new SecurityException("Groovy 沙箱拒绝属性写入: " + property);
    }

    @Override
    public Object onSetAttribute(Invoker invoker, Object receiver, String attribute, Object value) throws Throwable {
        throw new SecurityException("Groovy 沙箱拒绝字段写入: " + attribute);
    }

    @Override
    public Object onSetArray(Invoker invoker, Object receiver, Object index, Object value) throws Throwable {
        throw new SecurityException("Groovy 沙箱拒绝下标写入");
    }

    private static String receiverType(Object receiver) {
        return receiver == null ? "null" : receiver.getClass().getName();
    }
}
