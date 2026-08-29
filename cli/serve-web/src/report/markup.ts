// A deliberately small bitmap markup editor for bug-report captures.
//
// It edits the captured pixels, not the page. Four tools cover the useful bug-report vocabulary:
// box the bad area, point at it, circle it by hand, or label it. Every gesture is one undo step and
// Save returns a new PNG data URL; the caller owns persistence and re-uploading.

import type { Capture } from "./store.js";

export type MarkupTool = "box" | "arrow" | "pen" | "text";

interface Point {
    x: number;
    y: number;
}

export function markupEditor(
    capture: Capture,
    onSave: (capture: Capture) => void,
    onCancel: () => void,
): HTMLElement {
    const root = document.createElement("div");
    root.className = "cp-markup";
    const tools = document.createElement("div");
    tools.className = "cp-markup-tools";
    tools.setAttribute("role", "toolbar");
    tools.setAttribute("aria-label", "Image markup tools");
    let tool: MarkupTool = "box";
    const toolButtons = (
        [
            ["box", "Box"],
            ["arrow", "Arrow"],
            ["pen", "Pen"],
            ["text", "Text"],
        ] as const
    ).map(([value, label]) => {
        const button = buttonFor(label, () => {
            tool = value;
            toolButtons.forEach((item) =>
                item.setAttribute(
                    "aria-pressed",
                    item === button ? "true" : "false",
                ),
            );
        });
        button.classList.add("cp-markup-tool");
        button.setAttribute("aria-pressed", value === tool ? "true" : "false");
        return button;
    });
    const colour = document.createElement("input");
    colour.type = "color";
    colour.value = "#e00034";
    colour.className = "cp-markup-colour";
    colour.title = "Markup colour";
    colour.setAttribute("aria-label", "Markup colour");
    const text = document.createElement("input");
    text.type = "text";
    text.value = "Look here";
    text.className = "cp-markup-text";
    text.setAttribute("aria-label", "Text to place on the image");
    tools.append(...toolButtons, colour, text);

    const canvas = document.createElement("canvas");
    canvas.className = "cp-markup-canvas";
    canvas.width = capture.width;
    canvas.height = capture.height;
    canvas.setAttribute("aria-label", `Mark up ${capture.label}`);
    const context = canvas.getContext("2d");
    const committed = document.createElement("canvas");
    committed.width = canvas.width;
    committed.height = canvas.height;
    const committedContext = committed.getContext("2d");
    const history: string[] = [capture.dataUrl];
    let start: Point | null = null;
    let points: Point[] = [];
    let imageReady = false;
    let saveButton: HTMLButtonElement | null = null;

    const redrawCommitted = () => {
        if (!context) return;
        context.clearRect(0, 0, canvas.width, canvas.height);
        context.drawImage(committed, 0, 0);
    };
    // Which load owns the canvas. Undo pressed twice before the first PNG has
    // decoded starts two independent decodes, and nothing orders them: the older
    // one finishing last would paint a later state than `history` records, so the
    // canvas — and the save taken from it — disagree with the undo stack.
    let loadGeneration = 0;
    const load = (dataUrl: string) => {
        const generation = ++loadGeneration;
        imageReady = false;
        canvas.setAttribute("aria-busy", "true");
        if (saveButton) saveButton.disabled = true;
        const image = new Image();
        image.addEventListener("load", () => {
            if (generation !== loadGeneration) return;
            if (!context || !committedContext) return;
            committedContext.clearRect(0, 0, canvas.width, canvas.height);
            committedContext.drawImage(
                image,
                0,
                0,
                canvas.width,
                canvas.height,
            );
            redrawCommitted();
            imageReady = true;
            canvas.setAttribute("aria-busy", "false");
            if (saveButton) saveButton.disabled = false;
        });
        image.src = dataUrl;
    };
    load(capture.dataUrl);

    const draw = (end: Point) => {
        if (!context || !start) return;
        redrawCommitted();
        style(context, colour.value, canvas.width);
        if (tool === "box") {
            context.strokeRect(
                start.x,
                start.y,
                end.x - start.x,
                end.y - start.y,
            );
        } else if (tool === "arrow") {
            drawArrow(context, start, end);
        } else if (tool === "pen") {
            context.beginPath();
            context.moveTo(points[0]?.x ?? start.x, points[0]?.y ?? start.y);
            points
                .slice(1)
                .forEach((point) => context.lineTo(point.x, point.y));
            context.lineTo(end.x, end.y);
            context.stroke();
        }
    };
    const commit = () => {
        if (!context || !committedContext) return;
        committedContext.clearRect(0, 0, canvas.width, canvas.height);
        committedContext.drawImage(canvas, 0, 0);
        history.push(canvas.toDataURL("image/png"));
        if (history.length > 12) history.splice(1, 1);
    };

    canvas.addEventListener("pointerdown", (event) => {
        if (!imageReady) return;
        const point = canvasPoint(canvas, event);
        if (tool === "text") {
            if (!context || !text.value.trim()) return;
            redrawCommitted();
            style(context, colour.value, canvas.width);
            context.font = `600 ${Math.max(18, Math.round(canvas.width / 32))}px system-ui`;
            context.fillStyle = colour.value;
            context.fillText(text.value.trim(), point.x, point.y);
            commit();
            return;
        }
        start = point;
        points = [point];
        canvas.setPointerCapture(event.pointerId);
    });
    canvas.addEventListener("pointermove", (event) => {
        if (!start) return;
        const point = canvasPoint(canvas, event);
        if (tool === "pen") points.push(point);
        draw(point);
    });
    canvas.addEventListener("pointerup", (event) => {
        if (!start) return;
        draw(canvasPoint(canvas, event));
        commit();
        start = null;
        points = [];
    });
    canvas.addEventListener("pointercancel", () => {
        start = null;
        points = [];
        redrawCommitted();
    });

    const actions = document.createElement("div");
    actions.className = "cp-markup-actions";
    saveButton = buttonFor("Save markup", () => {
        onSave({
            ...capture,
            dataUrl: canvas.toDataURL("image/png"),
            uploadedUrl: undefined,
        });
    });
    saveButton.disabled = !imageReady;
    actions.append(
        buttonFor("Undo", () => {
            if (history.length <= 1) return;
            history.pop();
            load(history[history.length - 1]);
        }),
        saveButton,
        buttonFor("Cancel", onCancel),
    );
    root.append(tools, canvas, actions);
    return root;
}

function buttonFor(label: string, action: () => void): HTMLButtonElement {
    const button = document.createElement("button");
    button.type = "button";
    button.className = "cp-shot-action";
    button.textContent = label;
    button.addEventListener("click", action);
    return button;
}

function canvasPoint(canvas: HTMLCanvasElement, event: PointerEvent): Point {
    const rect = canvas.getBoundingClientRect();
    return {
        x:
            ((event.clientX - rect.left) * canvas.width) /
            Math.max(1, rect.width),
        y:
            ((event.clientY - rect.top) * canvas.height) /
            Math.max(1, rect.height),
    };
}

function style(
    context: CanvasRenderingContext2D,
    colour: string,
    width: number,
): void {
    context.strokeStyle = colour;
    context.lineWidth = Math.max(3, width / 180);
    context.lineCap = "round";
    context.lineJoin = "round";
}

export function drawArrow(
    context: CanvasRenderingContext2D,
    start: Point,
    end: Point,
): void {
    const angle = Math.atan2(end.y - start.y, end.x - start.x);
    const head = Math.max(14, context.lineWidth * 4);
    context.beginPath();
    context.moveTo(start.x, start.y);
    context.lineTo(end.x, end.y);
    context.moveTo(end.x, end.y);
    context.lineTo(
        end.x - head * Math.cos(angle - Math.PI / 6),
        end.y - head * Math.sin(angle - Math.PI / 6),
    );
    context.moveTo(end.x, end.y);
    context.lineTo(
        end.x - head * Math.cos(angle + Math.PI / 6),
        end.y - head * Math.sin(angle + Math.PI / 6),
    );
    context.stroke();
}
