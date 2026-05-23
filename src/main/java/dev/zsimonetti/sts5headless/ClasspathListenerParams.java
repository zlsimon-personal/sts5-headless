package dev.zsimonetti.sts5headless;

/**
 * Mirror of STS5's
 * {@code org.springframework.ide.vscode.commons.protocol.java.ClasspathListenerParams}
 * — the payload of the {@code sts/addClasspathListener} request. Public fields
 * so LSP4J's Gson binds it by name (decompiled field names: {@code
 * callbackCommandId}, {@code batched}).
 */
public final class ClasspathListenerParams {

    public String callbackCommandId;
    public boolean batched;

    @Override
    public String toString() {
        return "ClasspathListenerParams[callbackCommandId=" + callbackCommandId
                + ", batched=" + batched + "]";
    }
}
