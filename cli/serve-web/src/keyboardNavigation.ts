// Opt-in power-user keyboard navigation for every compose-preview serve page.
// Server-rendered controls stay the source of truth: this layer activates the same links, buttons,
// selects, and drawer toggles as pointer input, so it cannot drift from viewer.js or URL state.

const ENABLED_KEY = "cp-keyboard-navigation";
const ONBOARDED_KEY = "cp-keyboard-onboarded-v1";

type Section = "all" | "components" | "variants" | "modes" | "overrides";
type CommandSection = Exclude<Section, "all">;

interface Command {
    section: CommandSection;
    label: string;
    detail: string;
    keywords: string;
    href?: string;
    run: () => void;
}

function stored(key: string): string | null {
    try {
        return localStorage.getItem(key);
    } catch (_) {
        return null;
    }
}

function save(key: string, value: string): void {
    try {
        localStorage.setItem(key, value);
    } catch (_) {
        /* The page-local choice still works. */
    }
}

function text(element: Element | null): string {
    return (element?.textContent || "").replace(/\s+/g, " ").trim();
}

function controlLabel(control: HTMLElement): string {
    const explicit = control.getAttribute("aria-label");
    if (explicit) return explicit;
    const label = control.closest("label");
    const directText = Array.from(label?.childNodes || [])
        .filter((node) => node.nodeType === Node.TEXT_NODE)
        .map((node) => node.textContent || "")
        .join(" ")
        .replace(/\s+/g, " ")
        .replace(/:\s*$/, "")
        .trim();
    return directText || control.id;
}

function editable(target: EventTarget | null): boolean {
    const element = target instanceof Element ? target : null;
    return !!element?.closest(
        "input, textarea, select, [contenteditable='true']",
    );
}

function dedupe<T>(items: T[], key: (item: T) => string): T[] {
    const seen = new Set<string>();
    return items.filter((item) => {
        const value = key(item);
        if (!value || seen.has(value)) return false;
        seen.add(value);
        return true;
    });
}

function componentKey(element: HTMLAnchorElement): string {
    const targetId = element.hash.slice(1);
    const target = targetId ? document.getElementById(targetId) : null;
    return target instanceof HTMLAnchorElement &&
        target.matches(".cp-card[href]")
        ? target.href
        : element.href;
}

class KeyboardNavigation {
    private enabled = stored(ENABLED_KEY) === "1";
    private overlay: HTMLElement | null = null;
    private paletteInput: HTMLInputElement | null = null;
    private paletteList: HTMLElement | null = null;
    private section: Section = "all";
    private selected = 0;
    private commands: Command[] = [];
    private returnFocus: HTMLElement | null = null;
    private settingsInput: HTMLInputElement | null = null;

    constructor() {
        this.installSettings();
        this.renderHints();
        document.addEventListener(
            "keydown",
            (event) => this.onKeyDown(event),
            true,
        );
        if (this.enabled && stored(ONBOARDED_KEY) !== "1")
            this.openOnboarding();
    }

    private installSettings(): void {
        this.settingsInput = document.querySelector(
            "[data-cp-keyboard-navigation]",
        );
        if (!this.settingsInput) return;
        this.settingsInput.checked = this.enabled;
        this.settingsInput.addEventListener("change", () => {
            this.enabled = !!this.settingsInput?.checked;
            save(ENABLED_KEY, this.enabled ? "1" : "0");
            this.renderHints();
            if (this.enabled && stored(ONBOARDED_KEY) !== "1") {
                const settings = this.settingsInput?.closest("details");
                if (settings instanceof HTMLDetailsElement)
                    settings.open = false;
                this.openOnboarding();
            }
            if (!this.enabled) this.closeOverlay();
        });
        document
            .querySelector("[data-cp-keyboard-tour]")
            ?.addEventListener("click", () => {
                const settings = this.settingsInput?.closest("details");
                if (settings instanceof HTMLDetailsElement)
                    settings.open = false;
                if (this.enabled) this.openOnboarding();
                else this.openHelp();
            });
    }

    private renderHints(): void {
        document.getElementById("cp-keyboard-hints")?.remove();
        if (!this.enabled) return;
        const bar = document.createElement("aside");
        bar.id = "cp-keyboard-hints";
        bar.className = "cp-keyboard-hints";
        bar.setAttribute("aria-label", "Keyboard shortcuts");
        const isMac = /Mac|iPhone|iPad/.test(navigator.platform);
        const entries: Array<[string, string, Section]> = [
            [isMac ? "⌘K" : "Ctrl K", "Commands", "all"],
            ["C", "Components", "components"],
        ];
        if (document.querySelector(".cp-axes-tree, #cp-axes"))
            entries.push(["V", "Variants", "variants"]);
        if (document.querySelector(".cp-preview-primary"))
            entries.push(["M", "Modes", "modes"]);
        if (document.getElementById("cp-controls"))
            entries.push(["O", "Overrides", "overrides"]);
        entries.push(["?", "Help", "all"]);
        entries.forEach(([key, label, section]) => {
            const button = document.createElement("button");
            button.type = "button";
            button.innerHTML = `<kbd>${key}</kbd><span>${label}</span>`;
            button.addEventListener("click", () =>
                label === "Help" ? this.openHelp() : this.openPalette(section),
            );
            bar.appendChild(button);
        });
        document.body.appendChild(bar);
    }

    private onKeyDown(event: KeyboardEvent): void {
        if (this.overlay && event.key === "Escape") {
            event.preventDefault();
            this.closeOverlay();
            return;
        }
        if (!this.enabled) return;
        if (this.overlay) {
            return;
        }
        if (
            (event.metaKey || event.ctrlKey) &&
            event.key.toLowerCase() === "k"
        ) {
            event.preventDefault();
            this.openPalette("all");
            return;
        }
        if (
            event.metaKey ||
            event.ctrlKey ||
            event.altKey ||
            editable(event.target)
        )
            return;
        const key = event.key;
        if (key === "?") {
            event.preventDefault();
            this.openHelp();
        } else if (key.toLowerCase() === "c") {
            event.preventDefault();
            this.openPalette("components");
        } else if (key.toLowerCase() === "v") {
            event.preventDefault();
            this.openPalette("variants");
        } else if (key.toLowerCase() === "m") {
            event.preventDefault();
            this.openPalette("modes");
        } else if (key.toLowerCase() === "o") {
            event.preventDefault();
            this.openPalette("overrides");
        } else if (key === "j" || key === "k") {
            event.preventDefault();
            this.navigateRelative("components", key === "j" ? 1 : -1);
        } else if (key === "[" || key === "]") {
            event.preventDefault();
            this.navigateRelative("variants", key === "]" ? 1 : -1);
        }
    }

    private componentCommands(): Command[] {
        const navigationElements = Array.from(
            document.querySelectorAll<HTMLAnchorElement>(
                "#cp-nav-list .cp-nav-item, .cp-catalog-menu .cp-tree-component[href]",
            ),
        );
        const cardElements = Array.from(
            document.querySelectorAll<HTMLAnchorElement>(
                ".cp-card[href]:not(.cp-sys)",
            ),
        );
        return dedupe(
            [...navigationElements, ...cardElements],
            componentKey,
        ).map((element) => ({
            section: "components",
            label:
                text(element.querySelector(".cp-nav-name, .cp-label")) ||
                text(element),
            detail: element.getAttribute("title") || "Open component",
            keywords: `${text(element)} ${element.getAttribute("data-search") || ""}`,
            href: element.href,
            run: () => element.click(),
        }));
    }

    private variantCommands(): Command[] {
        const elements = Array.from(
            document.querySelectorAll<HTMLAnchorElement>(
                ".cp-axes-tree .cp-tree-component[href], .cp-axes-tree .cp-tree-variant[href], #cp-axes .cp-tree-component[href], #cp-axes .cp-tree-variant[href]",
            ),
        );
        return dedupe(elements, (element) => element.href).map((element) => ({
            section: "variants",
            label: text(element),
            detail: element.hasAttribute("aria-current")
                ? "Current variant"
                : "Switch variant",
            keywords: `${text(element)} ${element.title}`,
            href: element.href,
            run: () => element.click(),
        }));
    }

    private modeCommands(): Command[] {
        const commands: Command[] = [];
        document
            .querySelectorAll<HTMLButtonElement>(
                ".cp-preview-primary button:not([disabled]), .cp-theme-bar .cp-theme-btn:not([disabled])",
            )
            .forEach((button) => {
                const liveToggle = button.id === "cp-live-toggle";
                const selected = button.getAttribute("aria-pressed") === "true";
                const label = liveToggle
                    ? selected
                        ? "Static snapshot"
                        : "Live preview"
                    : text(button);
                if (!label) return;
                commands.push({
                    section: "modes",
                    label,
                    detail: liveToggle
                        ? selected
                            ? "Switch to static mode"
                            : "Switch to interactive mode"
                        : selected
                          ? "Selected"
                          : "Select mode",
                    keywords: `${label} ${text(button)} renderer theme format zoom`,
                    run: () => {
                        button.click();
                        this.closeOverlay();
                    },
                });
            });
        document
            .querySelectorAll<HTMLSelectElement>("#cp-lane-select")
            .forEach((select) => {
                Array.from(select.options).forEach((option) => {
                    if (option.disabled || !option.value) return;
                    commands.push({
                        section: "modes",
                        label: option.text.trim(),
                        detail: option.selected
                            ? "Selected renderer"
                            : "Select renderer",
                        keywords: `${option.text} renderer lane`,
                        run: () => {
                            select.value = option.value;
                            select.dispatchEvent(
                                new Event("change", { bubbles: true }),
                            );
                            this.closeOverlay();
                        },
                    });
                });
            });
        return dedupe(
            commands,
            (command) => `${command.label}|${command.detail}`,
        );
    }

    private overrideCommands(): Command[] {
        const controls = Array.from(
            document.querySelectorAll<HTMLInputElement | HTMLSelectElement>(
                "#cp-controls input:not([type='hidden']):not([disabled]), #cp-controls select:not([disabled])",
            ),
        ).filter(
            (control) =>
                !control.closest("[aria-hidden='true']") &&
                !control.closest("[hidden]"),
        );
        return controls.flatMap((control) => {
            const label = controlLabel(control);
            const value =
                control instanceof HTMLInputElement &&
                control.type === "checkbox"
                    ? control.checked
                        ? "On"
                        : "Off"
                    : control.value || "Default";
            const focus: Command = {
                section: "overrides" as const,
                label,
                detail: `Current: ${value}`,
                keywords: `${label} ${control.id} ${value}`,
                run: () => this.focusOverride(control),
            };
            if (!(control instanceof HTMLSelectElement)) return [focus];
            const choices = Array.from(control.options)
                .filter((option) => !option.disabled)
                .map<Command>((option) => ({
                    section: "overrides",
                    label: `${label}: ${option.text.trim()}`,
                    detail: option.selected ? "Selected" : "Apply override",
                    keywords: `${label} ${control.id} ${option.text} ${option.value}`,
                    run: () => {
                        control.value = option.value;
                        control.dispatchEvent(
                            new Event("change", { bubbles: true }),
                        );
                        this.closeOverlay();
                    },
                }));
            return [focus, ...choices];
        });
    }

    private allCommands(): Command[] {
        return [
            ...this.componentCommands(),
            ...this.variantCommands(),
            ...this.modeCommands(),
            ...this.overrideCommands(),
        ];
    }

    private focusOverride(control: HTMLElement): void {
        const viewer = document.querySelector(".cp-viewer");
        if (viewer && !viewer.classList.contains("cp-controls-open"))
            document.getElementById("cp-controls-toggle")?.click();
        const group = control.closest("details");
        if (group instanceof HTMLDetailsElement) group.open = true;
        this.closeOverlay(false);
        if (
            control instanceof HTMLInputElement &&
            control.type === "checkbox"
        ) {
            control.click();
            return;
        }
        requestAnimationFrame(() => {
            control.focus();
            control.scrollIntoView({ block: "center", behavior: "smooth" });
        });
    }

    private navigateRelative(
        section: "components" | "variants",
        direction: number,
    ): void {
        const commands =
            section === "components"
                ? this.componentCommands()
                : this.variantCommands();
        if (!commands.length) return;
        const currentHref = location.href;
        let current = commands.findIndex(
            (command) => command.href === currentHref,
        );
        if (current < 0 && !location.hash) {
            current = commands.findIndex(
                (command) =>
                    command.href != null &&
                    !new URL(command.href).hash &&
                    command.href.split("#")[0] === currentHref,
            );
        }
        if (current < 0) current = direction > 0 ? -1 : 0;
        commands[
            (current + direction + commands.length) % commands.length
        ].run();
    }

    private shell(kind: string, labelledBy: string): HTMLElement {
        const previousReturnFocus = this.returnFocus;
        this.closeOverlay(false);
        this.returnFocus =
            previousReturnFocus ||
            (document.activeElement instanceof HTMLElement
                ? document.activeElement
                : null);
        const overlay = document.createElement("div");
        overlay.className = "cp-keyboard-overlay";
        overlay.innerHTML = `<section class="cp-keyboard-dialog ${kind}" role="dialog" aria-modal="true" aria-labelledby="${labelledBy}"></section>`;
        overlay.addEventListener("mousedown", (event) => {
            if (event.target === overlay) this.closeOverlay();
        });
        overlay.addEventListener("keydown", (event) => {
            if (event.key !== "Tab") return;
            const focusable = Array.from(
                overlay.querySelectorAll<HTMLElement>(
                    "button:not([disabled]), input:not([disabled]), select:not([disabled]), a[href], [tabindex]:not([tabindex='-1'])",
                ),
            );
            if (!focusable.length) return;
            const first = focusable[0];
            const last = focusable[focusable.length - 1];
            if (event.shiftKey && document.activeElement === first) {
                event.preventDefault();
                last.focus();
            } else if (!event.shiftKey && document.activeElement === last) {
                event.preventDefault();
                first.focus();
            }
        });
        document.body.appendChild(overlay);
        document.documentElement.classList.add("cp-keyboard-modal-open");
        this.overlay = overlay;
        return overlay.querySelector("section")!;
    }

    private openPalette(section: Section): void {
        this.section = section;
        this.commands = this.allCommands().filter(
            (command) => section === "all" || command.section === section,
        );
        this.selected = 0;
        const dialog = this.shell("cp-command-palette", "cp-command-title");
        const title =
            section === "all"
                ? "Keyboard commands"
                : section[0].toUpperCase() + section.slice(1);
        dialog.innerHTML = `<header><div><span class="cp-keyboard-eyebrow">compose-preview</span><h2 id="cp-command-title">${title}</h2></div><button type="button" class="cp-keyboard-close" aria-label="Close">×</button></header>
          <label class="cp-command-search"><span aria-hidden="true">⌕</span><input type="search" autocomplete="off" placeholder="Search ${section === "all" ? "components, variants, modes, and overrides" : section}" aria-label="Search commands" aria-controls="cp-command-list"></label>
          <div class="cp-command-list" id="cp-command-list" role="listbox" aria-label="Commands"></div>
          <footer><span><kbd>↑</kbd><kbd>↓</kbd> move</span><span><kbd>Enter</kbd> select</span><span><kbd>Esc</kbd> close</span></footer>`;
        dialog
            .querySelector(".cp-keyboard-close")
            ?.addEventListener("click", () => this.closeOverlay());
        this.paletteInput = dialog.querySelector("input");
        this.paletteList = dialog.querySelector(".cp-command-list");
        this.paletteInput?.addEventListener("input", () => {
            this.selected = 0;
            this.renderCommands();
        });
        dialog.addEventListener("keydown", (event) => {
            const shown = this.filteredCommands();
            if (event.key === "ArrowDown" || event.key === "ArrowUp") {
                event.preventDefault();
                this.selected =
                    (this.selected +
                        (event.key === "ArrowDown" ? 1 : -1) +
                        shown.length) %
                    Math.max(1, shown.length);
                this.renderCommands();
            } else if (
                event.key === "Enter" &&
                event.target === this.paletteInput &&
                shown[this.selected]
            ) {
                event.preventDefault();
                shown[this.selected].run();
            }
        });
        this.renderCommands();
        this.paletteInput?.focus();
    }

    private filteredCommands(): Command[] {
        const query = (this.paletteInput?.value || "").trim().toLowerCase();
        if (!query) return this.commands;
        const words = query.split(/\s+/);
        return this.commands.filter((command) => {
            const haystack =
                `${command.label} ${command.detail} ${command.keywords}`.toLowerCase();
            return words.every((word) => haystack.includes(word));
        });
    }

    private renderCommands(): void {
        if (!this.paletteList) return;
        const commands = this.filteredCommands();
        if (this.selected >= commands.length)
            this.selected = Math.max(0, commands.length - 1);
        this.paletteList.innerHTML = "";
        if (!commands.length) {
            this.paletteList.innerHTML =
                '<p class="cp-command-empty">No matching commands.</p>';
            return;
        }
        let previous = "";
        commands.forEach((command, index) => {
            if (this.section === "all" && command.section !== previous) {
                const heading = document.createElement("div");
                heading.className = "cp-command-section";
                heading.textContent = command.section;
                this.paletteList?.appendChild(heading);
                previous = command.section;
            }
            const button = document.createElement("button");
            button.type = "button";
            button.tabIndex = -1;
            button.className = "cp-command-item";
            button.id = `cp-command-option-${index}`;
            button.setAttribute("role", "option");
            button.setAttribute(
                "aria-selected",
                index === this.selected ? "true" : "false",
            );
            const label = document.createElement("span");
            label.textContent = command.label;
            const detail = document.createElement("small");
            detail.textContent = command.detail;
            button.append(label, detail);
            button.addEventListener("mouseenter", () => {
                this.selected = index;
                this.paintSelection();
            });
            button.addEventListener("click", command.run);
            this.paletteList?.appendChild(button);
        });
        this.paintSelection();
    }

    private paintSelection(): void {
        const items = Array.from(
            this.paletteList?.querySelectorAll<HTMLElement>(
                ".cp-command-item",
            ) || [],
        );
        items.forEach((item, index) =>
            item.setAttribute(
                "aria-selected",
                index === this.selected ? "true" : "false",
            ),
        );
        this.paletteInput?.setAttribute(
            "aria-activedescendant",
            items[this.selected]?.id || "",
        );
        items[this.selected]?.scrollIntoView({ block: "nearest" });
    }

    private openHelp(): void {
        const dialog = this.shell("cp-shortcut-help", "cp-shortcut-title");
        dialog.innerHTML = `<header><div><span class="cp-keyboard-eyebrow">Power user guide</span><h2 id="cp-shortcut-title">Keyboard shortcuts</h2></div><button type="button" class="cp-keyboard-close" aria-label="Close">×</button></header>
          <div class="cp-shortcut-grid"><kbd>⌘/Ctrl K</kbd><span>Search every available command</span>
          <kbd>C</kbd><span>Jump to a component</span><kbd>J / K</kbd><span>Next / previous component</span>
          <kbd>V</kbd><span>Choose a state or variant</span><kbd>[ / ]</kbd><span>Previous / next variant</span>
          <kbd>M</kbd><span>Choose renderer, theme, or display mode</span><kbd>O</kbd><span>Find and focus an override</span>
          <kbd>?</kbd><span>Show this guide</span><kbd>Esc</kbd><span>Close any keyboard panel</span></div>
          <p class="cp-keyboard-note">Shortcuts pause while you type in a field. Tab and arrow keys keep their native browser behavior.</p>`;
        dialog
            .querySelector(".cp-keyboard-close")
            ?.addEventListener("click", () => this.closeOverlay());
        (dialog.querySelector(".cp-keyboard-close") as HTMLElement)?.focus();
    }

    private openOnboarding(): void {
        const dialog = this.shell(
            "cp-keyboard-onboarding",
            "cp-onboarding-title",
        );
        dialog.innerHTML = `<span class="cp-keyboard-eyebrow">Keyboard navigation is on</span>
          <h2 id="cp-onboarding-title">Move through previews at thought speed.</h2>
          <p>Jump straight to a component, switch variants and renderers, or find any override without leaving the keyboard.</p>
          <div class="cp-onboarding-demo" aria-label="Example shortcuts"><span><kbd>C</kbd> component</span><span><kbd>V</kbd> variant</span><span><kbd>M</kbd> mode</span><span><kbd>O</kbd> override</span></div>
          <p>The hint rail stays on screen. Press <kbd>?</kbd> whenever you want the full map.</p>
          <footer><button type="button" class="cp-onboarding-later">Not now</button><button type="button" class="cp-onboarding-start">Show commands</button></footer>`;
        dialog
            .querySelector(".cp-onboarding-later")
            ?.addEventListener("click", () => {
                save(ONBOARDED_KEY, "1");
                this.closeOverlay();
            });
        dialog
            .querySelector(".cp-onboarding-start")
            ?.addEventListener("click", () => {
                save(ONBOARDED_KEY, "1");
                this.openPalette("all");
            });
        (dialog.querySelector(".cp-onboarding-start") as HTMLElement)?.focus();
    }

    private closeOverlay(restore = true): void {
        if (!this.overlay) return;
        this.overlay.remove();
        this.overlay = null;
        this.paletteInput = null;
        this.paletteList = null;
        document.documentElement.classList.remove("cp-keyboard-modal-open");
        if (restore) this.returnFocus?.focus();
        this.returnFocus = null;
    }
}

new KeyboardNavigation();
