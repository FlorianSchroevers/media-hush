package de.robv.android.xposed;
import de.robv.android.xposed.callbacks.XCallback;
public abstract class XC_MethodHook extends XCallback {
    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {}
    protected void afterHookedMethod(MethodHookParam param) throws Throwable {}
    public static final class MethodHookParam extends XCallback.Param {
        public java.lang.reflect.Member method;
        public Object thisObject;
        public Object[] args;
        public void setResult(Object result) { throw new RuntimeException("stub"); }
    }
    public class Unhook {}
}
