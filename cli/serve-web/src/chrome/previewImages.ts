/** Replace browser broken-image chrome with shared loading, error, and retry states. */
export function installPreviewImageStates(): void {
    const imagesFor = (host: HTMLElement): HTMLImageElement[] =>
        Array.from(host.querySelectorAll<HTMLImageElement>("img")).filter(
            (candidate) => candidate.closest(".cp-imgwrap, .cp-stage") === host,
        );

    const clearError = (host: HTMLElement): void =>
        host.querySelector(".cp-image-error")?.remove();

    const syncHost = (host: HTMLElement): void => {
        clearError(host);
        const active = imagesFor(host).find(
            (candidate) => !candidate.hidden && !!candidate.getAttribute("src"),
        );
        if (!active) {
            delete host.dataset.imageState;
            return;
        }
        const imageState = active.dataset.cpImageState;
        if (imageState) host.dataset.imageState = imageState;
        else delete host.dataset.imageState;
        if (imageState !== "error") return;

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
            const src = active.getAttribute("src") || "";
            if (!src) return;
            active.dataset.cpImageState = "loading";
            syncHost(host);
            active.removeAttribute("src");
            requestAnimationFrame(() => active.setAttribute("src", src));
        });
        state.append(message, retry);
        host.append(state);
    };

    const wire = (img: HTMLImageElement): void => {
        if (img.dataset.cpImageStateWired === "1") return;
        const host = img.closest<HTMLElement>(".cp-imgwrap, .cp-stage");
        if (!host) return;
        img.dataset.cpImageStateWired = "1";

        const loading = (): void => {
            if (img.getAttribute("src")) img.dataset.cpImageState = "loading";
            else delete img.dataset.cpImageState;
            syncHost(host);
        };
        const loaded = (): void => {
            img.dataset.cpImageState = "loaded";
            syncHost(host);
        };
        const failed = (): void => {
            img.dataset.cpImageState = "error";
            syncHost(host);
        };

        img.addEventListener("load", loaded);
        img.addEventListener("error", failed);
        new MutationObserver((records) => {
            if (records.some((record) => record.attributeName === "src"))
                loading();
            else syncHost(host);
        }).observe(img, {
            attributes: true,
            attributeFilter: ["src", "hidden"],
        });
        if (img.complete && img.naturalWidth > 0) loaded();
        else if (img.complete && img.getAttribute("src")) failed();
        else loading();
    };

    const scan = (): void =>
        document
            .querySelectorAll<HTMLImageElement>(
                ".cp-imgwrap img, .cp-stage > img",
            )
            .forEach(wire);
    if (document.readyState === "loading")
        document.addEventListener("DOMContentLoaded", scan, { once: true });
    else scan();
}
