package ${package};

import com.google.gwt.core.client.EntryPoint;
import elemental2.dom.DomGlobal;
import elemental2.dom.HTMLButtonElement;

public class ${module} implements EntryPoint {

    @Override
    public void onModuleLoad() {
        HTMLButtonElement btn = (HTMLButtonElement) DomGlobal.document.createElement("button");
        btn.textContent = "J2CL !!!";
        DomGlobal.document.body.appendChild(btn);
    }
}
