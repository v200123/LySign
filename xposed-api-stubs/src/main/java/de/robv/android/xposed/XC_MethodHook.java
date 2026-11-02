package de.robv.android.xposed;

public abstract class XC_MethodHook {
    public static class Unhook {
    }

    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
    }

    public static class MethodHookParam {
        public Object thisObject;
        public Object[] args;

        public Object getResult() {
            return null;
        }
    }
}
