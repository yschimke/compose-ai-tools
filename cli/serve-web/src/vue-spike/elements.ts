// Non-shipping feasibility probes for the Lit -> Vue migration.
//
// These tags are imported only by `test/vueSpike.test.ts`; `src/main.ts` deliberately does not
// import them, so no production page or committed bundle carries Vue until every Lit element can
// move atomically. Together they exercise the three shapes serve-web actually has: a control that
// owns markup, a controller over server-rendered markup, and an async data-driven component.

import {
    defineCustomElement,
    h,
    onBeforeUnmount,
    onMounted,
    ref,
    useHost,
} from "vue";

export const VueRenderSpike = defineCustomElement(
    {
        name: "CpVueRenderSpike",
        props: { label: { type: String, default: "Increment" } },
        setup(props) {
            const count = ref(0);
            return () =>
                h(
                    "button",
                    {
                        type: "button",
                        "aria-label": props.label,
                        onClick: () => count.value++,
                    },
                    String(count.value),
                );
        },
    },
    { shadowRoot: false },
);

export const VueControllerSpike = defineCustomElement(
    {
        name: "CpVueControllerSpike",
        setup() {
            const host = useHost()!;
            let target: HTMLElement | null = null;
            const clicked = () => {
                const next = Number(host.getAttribute("data-count") ?? "0") + 1;
                host.setAttribute("data-count", String(next));
            };
            onMounted(() => {
                target = document.querySelector("[data-vue-spike-target]");
                target?.addEventListener("click", clicked);
            });
            onBeforeUnmount(() => {
                target?.removeEventListener("click", clicked);
                target = null;
            });
            return () => null;
        },
    },
    { shadowRoot: false },
);

export const VueAsyncSpike = defineCustomElement(
    {
        name: "CpVueAsyncSpike",
        props: { src: { type: String, required: true } },
        setup(props) {
            const rows = ref<string[]>([]);
            let connected = true;
            onMounted(async () => {
                const response = await fetch(props.src);
                const value = (await response.json()) as unknown;
                if (connected && Array.isArray(value)) {
                    rows.value = value.filter(
                        (entry): entry is string => typeof entry === "string",
                    );
                }
            });
            onBeforeUnmount(() => {
                connected = false;
            });
            return () =>
                rows.value.length
                    ? h(
                          "ul",
                          rows.value.map((row) => h("li", row)),
                      )
                    : null;
        },
    },
    { shadowRoot: false },
);

customElements.define("cp-vue-render-spike", VueRenderSpike);
customElements.define("cp-vue-controller-spike", VueControllerSpike);
customElements.define("cp-vue-async-spike", VueAsyncSpike);

declare global {
    interface HTMLElementTagNameMap {
        "cp-vue-render-spike": InstanceType<typeof VueRenderSpike>;
        "cp-vue-controller-spike": InstanceType<typeof VueControllerSpike>;
        "cp-vue-async-spike": InstanceType<typeof VueAsyncSpike>;
    }
}
