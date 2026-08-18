/** Replace browser broken-image chrome with shared loading, error, and retry states. */
export function installPreviewImageStates(): void {
    const wire = (img: HTMLImageElement): void => {
        if (img.dataset.cpImageStateWired === "1") return;
        const host = img.closest<HTMLElement>(".cp-imgwrap, .cp-stage");
        if (!host) return;
        img.dataset.cpImageStateWired = "1";

        const clearError = (): void => host.querySelector(".cp-image-error")?.remove();
        const loading = (): void => {
            clearError();
            if (img.getAttribute("src")) host.dataset.imageState = "loading";
        };
        const loaded = (): void => {
            clearError();
            host.dataset.imageState = "loaded";
        };
        const failed = (): void => {
            host.dataset.imageState = "error";
            clearError();
            const state = document.createElement("div");
            state.className = "cp-image-error";
            state.setAttribute("role", "alert");
            const message = document.createElement("span");
            message.textContent = "Preview image failed to load.";
            const retry = document.createElement("button");
            retry.type = "button";
            retry.textContent = "Retry";
            retry.addEventListener("click", (event) => {
                event.preventDefault();
                event.stopPropagation();
                const src = img.getAttribute("src") || "";
                if (!src) return;
                loading();
                img.removeAttribute("src");
                requestAnimationFrame(() => img.setAttribute("src", src));
            });
            state.append(message, retry);
            host.append(state);
        };

        img.addEventListener("load", loaded);
        img.addEventListener("error", failed);
        new MutationObserver(loading).observe(img, {
            attributes: true,
            attributeFilter: ["src"],
        });
        if (img.complete && img.naturalWidth > 0) loaded();
        else loading();
    };

    const scan = (): void =>
        document
            .querySelectorAll<HTMLImageElement>(".cp-imgwrap img, .cp-stage > img")
            .forEach(wire);
    if (document.readyState === "loading")
        document.addEventListener("DOMContentLoaded", scan, { once: true });
    else scan();
}
