// Operations: opcode-to-reader registry for deserializing binary operations.

import type { CompanionOperationFn } from './CompanionOperation';

// Import all operation classes
import { Header } from './operations/Header';
import { DrawRect } from './operations/DrawRect';
import { DrawCircle } from './operations/DrawCircle';
import { DrawOval } from './operations/DrawOval';
import { DrawRoundRect } from './operations/DrawRoundRect';
import { DrawArc } from './operations/DrawArc';
import { DrawSector } from './operations/DrawSector';
import { DrawPath } from './operations/DrawPath';
import { DrawTweenPath } from './operations/DrawTweenPath';
import { DrawContent } from './operations/DrawContent';
import { DrawBitmap } from './operations/DrawBitmap';
import { DrawBitmapInt } from './operations/DrawBitmapInt';
import { DrawBitmapScaled } from './operations/DrawBitmapScaled';
import { DrawText } from './operations/DrawText';
import { DrawTextOnPath } from './operations/DrawTextOnPath';
import { DrawToBitmap } from './operations/DrawToBitmap';
import { DrawLine } from './operations/DrawLine';
import { DrawTextAnchored } from './operations/DrawTextAnchored';
import {
    MatrixSave, MatrixRestore, MatrixTranslate,
    MatrixScale, MatrixRotate, MatrixSkew,
    MatrixFromPath, ClipRect, ClipPath
} from './operations/MatrixOperations';
import {
    TextData, BitmapData, PaintData, PathData,
    FloatConstant, ColorConstant, Theme, ClickArea,
    NamedVariable, RootContentDescription, RootContentBehavior
} from './operations/DataOperations';
import { FloatExpression } from './operations/FloatExpression';
import { ColorExpression } from './operations/ColorExpression';
import { IntegerExpression } from './operations/IntegerExpression';
import { IntegerConstant } from './operations/IntegerConstant';
import { BooleanConstant } from './operations/BooleanConstant';
import { LongConstant } from './operations/LongConstant';
import { ShaderData } from './operations/ShaderData';
import { TextFromFloat } from './operations/TextFromFloat';
import { TextMerge } from './operations/TextMerge';
import { ComponentValue } from './operations/ComponentValue';
import { DataMapIds } from './operations/DataMapIds';
import { DataListIds } from './operations/DataListIds';
import { DataListFloat } from './operations/DataListFloat';
import { ContainerEnd } from './operations/layout/ContainerEnd';
import { RootLayoutComponent } from './operations/layout/RootLayoutComponent';
import { LayoutComponentContent } from './operations/layout/LayoutComponentContent';
import { CanvasContent } from './operations/layout/CanvasContent';
import { BoxLayout } from './operations/layout/managers/BoxLayout';
import { RowLayout } from './operations/layout/managers/RowLayout';
import { ColumnLayout } from './operations/layout/managers/ColumnLayout';
import { CanvasLayout } from './operations/layout/managers/CanvasLayout';
import {
    WidthModifier, HeightModifier, WidthInModifier, HeightInModifier,
    CollapsiblePriorityModifier, BackgroundModifier, BorderModifier,
    PaddingModifier, RoundedClipRectModifier, ClipRectModifier,
    ClickModifier, MultiClickModifier, DimensionConstraintsModifier,
    TouchDownModifier, TouchUpModifier, TouchCancelModifier,
    VisibilityModifier, OffsetModifier, ZIndexModifier, GraphicsLayerModifier,
    ScrollModifier, MarqueeModifier, RippleModifier, DrawContentModifier,
    AlignByModifier
} from './operations/layout/modifiers/ModifierOperations';
import { Skip } from './operations/Skip';
import { TextStyle } from './operations/layout/managers/TextStyle';
import { TouchExpression } from './operations/TouchExpression';
import { ConditionalOperations } from './operations/ConditionalOperations';
import { PathCreate } from './operations/PathCreate';
import { PathAppend } from './operations/PathAppend';
import { PathExpression } from './operations/PathExpression';
import { MatrixExpression } from './operations/MatrixExpression';
import { MatrixConstant } from './operations/MatrixConstant';
import { MatrixVectorMath } from './operations/MatrixVectorMath';
import { TextTransform } from './operations/TextTransform';
import { TextLookup } from './operations/TextLookup';
import { ColorTheme } from './operations/ColorTheme';
import { ColorAttribute } from './operations/ColorAttribute';
import { DataMapLookup } from './operations/DataMapLookup';
import { TextMeasure } from './operations/TextMeasure';
import { TextAttribute } from './operations/TextAttribute';
import { TextLength } from './operations/TextLength';
import { TextSubtext } from './operations/TextSubtext';
import {
    ImpulseOperation, ImpulseProcess, CanvasOperationsOp,
    DebugMessage,
    HostActionMetadataOperation, RunActionOperation,
    ValueFloatExpressionChangeAction, TextLayout,
    PathTween, HapticFeedback, WakeIn, TimeAttribute
} from './operations/StubOperations';
import {
    ParticlesCreateOp, ParticlesLoopOp, ParticlesCompareOp
} from './operations/ParticleOperations';
import { FlowLayout } from './operations/layout/managers/FlowLayout';
import { LoopOperation } from './operations/layout/LoopOperation';
import { CoreText } from './operations/layout/managers/CoreText';
import { FitBoxLayout } from './operations/layout/managers/FitBoxLayout';
import { CollapsibleRowLayout } from './operations/layout/managers/CollapsibleRowLayout';
import { CollapsibleColumnLayout } from './operations/layout/managers/CollapsibleColumnLayout';
import { IdLookup } from './operations/IdLookup';
import { DataDynamicListFloat } from './operations/DataDynamicListFloat';
import { LayoutComputeOperation } from './operations/layout/modifiers/LayoutComputeOperation';
import { UpdateDynamicFloatList } from './operations/UpdateDynamicFloatList';
import { ImageLayout } from './operations/layout/managers/ImageLayout';
import { StateLayout } from './operations/layout/managers/StateLayout';
import {
    HostActionOperation, HostNamedActionOperation,
    ValueIntegerChangeAction, ValueStringChangeAction,
    ValueIntegerExpressionChangeAction, ValueFloatChangeAction
} from './operations/layout/modifiers/ActionOperations';
import { SoundData, SoundExpression, PlaySound } from './operations/SoundOperations';
import {
    ReferencedOperations, IncludeReferencedOperations,
    PatternInflation, PatternBlock, PatternForEach,
    PatternArgument, PatternDefine
} from './operations/loom/PatternOperations';
import { Custom } from './operations/layout/managers/Custom';

export class Operations {
    private static readonly sMap = new Map<number, CompanionOperationFn>();
    private static initialized = false;

    private static init(): void {
        if (Operations.initialized) return;
        Operations.initialized = true;

        const m = Operations.sMap;

        // Header
        m.set(Header.OP_CODE, Header.read);

        // Draw operations
        m.set(DrawRect.OP_CODE, DrawRect.read);
        m.set(DrawCircle.OP_CODE, DrawCircle.read);
        m.set(DrawLine.OP_CODE, DrawLine.read);
        m.set(DrawOval.OP_CODE, DrawOval.read);
        m.set(DrawRoundRect.OP_CODE, DrawRoundRect.read);
        m.set(DrawArc.OP_CODE, DrawArc.read);
        m.set(DrawSector.OP_CODE, DrawSector.read);
        m.set(DrawPath.OP_CODE, DrawPath.read);
        m.set(DrawTweenPath.OP_CODE, DrawTweenPath.read);
        m.set(DrawContent.OP_CODE, DrawContent.read);
        m.set(DrawBitmap.OP_CODE, DrawBitmap.read);
        m.set(DrawBitmapInt.OP_CODE, DrawBitmapInt.read);
        m.set(DrawBitmapScaled.OP_CODE, DrawBitmapScaled.read);
        m.set(DrawText.OP_CODE, DrawText.read);
        m.set(DrawTextAnchored.OP_CODE, DrawTextAnchored.read);
        m.set(DrawTextOnPath.OP_CODE, DrawTextOnPath.read);
        m.set(DrawToBitmap.OP_CODE, DrawToBitmap.read);

        // Matrix & clip operations
        m.set(MatrixSave.OP_CODE, MatrixSave.read);
        m.set(MatrixRestore.OP_CODE, MatrixRestore.read);
        m.set(MatrixTranslate.OP_CODE, MatrixTranslate.read);
        m.set(MatrixScale.OP_CODE, MatrixScale.read);
        m.set(MatrixRotate.OP_CODE, MatrixRotate.read);
        m.set(MatrixSkew.OP_CODE, MatrixSkew.read);
        m.set(MatrixFromPath.OP_CODE, MatrixFromPath.read);
        m.set(ClipRect.OP_CODE, ClipRect.read);
        m.set(ClipPath.OP_CODE, ClipPath.read);

        // Data operations
        m.set(TextData.OP_CODE, TextData.read);
        m.set(BitmapData.OP_CODE, BitmapData.read);
        m.set(PaintData.OP_CODE, PaintData.read);
        m.set(PathData.OP_CODE, PathData.read);
        m.set(FloatConstant.OP_CODE, FloatConstant.read);
        m.set(ColorConstant.OP_CODE, ColorConstant.read);
        m.set(Theme.OP_CODE, Theme.read);
        m.set(ClickArea.OP_CODE, ClickArea.read);
        m.set(NamedVariable.OP_CODE, NamedVariable.read);
        m.set(RootContentDescription.OP_CODE, RootContentDescription.read);
        m.set(RootContentBehavior.OP_CODE, RootContentBehavior.read);
        m.set(ShaderData.OP_CODE, ShaderData.read);

        // Expression & constant operations
        m.set(FloatExpression.OP_CODE, FloatExpression.read);
        m.set(ColorExpression.OP_CODE, ColorExpression.read);
        m.set(IntegerExpression.OP_CODE, IntegerExpression.read);
        m.set(IntegerConstant.OP_CODE, IntegerConstant.read);
        m.set(BooleanConstant.OP_CODE, BooleanConstant.read);
        m.set(LongConstant.OP_CODE, LongConstant.read);
        m.set(TextFromFloat.OP_CODE, TextFromFloat.read);
        m.set(TextMerge.OP_CODE, TextMerge.read);
        m.set(ComponentValue.OP_CODE, ComponentValue.read);
        m.set(DataMapIds.OP_CODE, DataMapIds.read);
        m.set(DataMapLookup.OP_CODE, DataMapLookup.read);
        m.set(TextMeasure.OP_CODE, TextMeasure.read);
        m.set(TextLength.OP_CODE, TextLength.read);
        m.set(TextSubtext.OP_CODE, TextSubtext.read);
        m.set(DataListIds.OP_CODE, DataListIds.read);
        m.set(DataListFloat.OP_CODE, DataListFloat.read);

        // Layout
        m.set(RootLayoutComponent.OP_CODE, RootLayoutComponent.read);
        m.set(LayoutComponentContent.OP_CODE, LayoutComponentContent.read);
        m.set(CanvasContent.OP_CODE, CanvasContent.read);
        m.set(BoxLayout.OP_CODE, BoxLayout.read);
        m.set(RowLayout.OP_CODE, RowLayout.read);
        m.set(ColumnLayout.OP_CODE, ColumnLayout.read);
        m.set(CanvasLayout.OP_CODE, CanvasLayout.read);
        m.set(ContainerEnd.OP_CODE, ContainerEnd.read);

        // Modifiers
        m.set(WidthModifier.OP_CODE, WidthModifier.read);
        m.set(HeightModifier.OP_CODE, HeightModifier.read);
        m.set(WidthInModifier.OP_CODE, WidthInModifier.read);
        m.set(HeightInModifier.OP_CODE, HeightInModifier.read);
        m.set(CollapsiblePriorityModifier.OP_CODE, CollapsiblePriorityModifier.read);
        m.set(BackgroundModifier.OP_CODE, BackgroundModifier.read);
        m.set(BorderModifier.OP_CODE, BorderModifier.read);
        m.set(PaddingModifier.OP_CODE, PaddingModifier.read);
        m.set(RoundedClipRectModifier.OP_CODE, RoundedClipRectModifier.read);
        m.set(ClipRectModifier.OP_CODE, ClipRectModifier.read);
        m.set(ClickModifier.OP_CODE, ClickModifier.read);
        m.set(MultiClickModifier.OP_CODE, MultiClickModifier.read);
        m.set(DimensionConstraintsModifier.OP_CODE, DimensionConstraintsModifier.read);
        m.set(TouchDownModifier.OP_CODE, TouchDownModifier.read);
        m.set(TouchUpModifier.OP_CODE, TouchUpModifier.read);
        m.set(TouchCancelModifier.OP_CODE, TouchCancelModifier.read);
        m.set(VisibilityModifier.OP_CODE, VisibilityModifier.read);
        m.set(OffsetModifier.OP_CODE, OffsetModifier.read);
        m.set(ZIndexModifier.OP_CODE, ZIndexModifier.read);
        m.set(GraphicsLayerModifier.OP_CODE, GraphicsLayerModifier.read);
        m.set(ScrollModifier.OP_CODE, ScrollModifier.read);
        m.set(MarqueeModifier.OP_CODE, MarqueeModifier.read);
        m.set(RippleModifier.OP_CODE, RippleModifier.read);
        m.set(DrawContentModifier.OP_CODE, DrawContentModifier.read);
        m.set(AlignByModifier.OP_CODE, AlignByModifier.read);

        // Misc operations
        m.set(TouchExpression.OP_CODE, TouchExpression.read);
        m.set(ConditionalOperations.OP_CODE, ConditionalOperations.read);
        m.set(PathCreate.OP_CODE, PathCreate.read);
        m.set(TextTransform.OP_CODE, TextTransform.read);
        m.set(MatrixExpression.OP_CODE, MatrixExpression.read);
        m.set(PathExpression.OP_CODE, PathExpression.read);
        m.set(TextLookup.OP_CODE, TextLookup.read);
        m.set(ColorTheme.OP_CODE, ColorTheme.read);
        m.set(ColorAttribute.OP_CODE, ColorAttribute.read);
        m.set(TextLayout.OP_CODE, TextLayout.read);
        m.set(PathAppend.OP_CODE, PathAppend.read);
        m.set(ImpulseOperation.OP_CODE, ImpulseOperation.read);
        m.set(TextAttribute.OP_CODE, TextAttribute.read);
        m.set(CanvasOperationsOp.OP_CODE, CanvasOperationsOp.read);
        m.set(DebugMessage.OP_CODE, DebugMessage.read);
        m.set(MatrixVectorMath.OP_CODE, MatrixVectorMath.read);
        m.set(MatrixConstant.OP_CODE, MatrixConstant.read);
        m.set(ParticlesCreateOp.OP_CODE, ParticlesCreateOp.read);
        m.set(HostActionMetadataOperation.OP_CODE, HostActionMetadataOperation.read);
        m.set(RunActionOperation.OP_CODE, RunActionOperation.read);
        m.set(ImpulseProcess.OP_CODE, ImpulseProcess.read);
        m.set(FlowLayout.OP_CODE, FlowLayout.read);
        m.set(ValueFloatExpressionChangeAction.OP_CODE, ValueFloatExpressionChangeAction.read);
        m.set(ParticlesCompareOp.OP_CODE, ParticlesCompareOp.read);
        m.set(ParticlesLoopOp.OP_CODE, ParticlesLoopOp.read);

        // Loop
        m.set(LoopOperation.OP_CODE, LoopOperation.read);

        // CoreText
        m.set(CoreText.OP_CODE, CoreText.read);

        // FitBox, Collapsible layouts
        m.set(FitBoxLayout.OP_CODE, FitBoxLayout.read);
        m.set(CollapsibleRowLayout.OP_CODE, CollapsibleRowLayout.read);
        m.set(CollapsibleColumnLayout.OP_CODE, CollapsibleColumnLayout.read);

        // IdLookup, DataDynamicListFloat
        m.set(IdLookup.OP_CODE, IdLookup.read);
        m.set(DataDynamicListFloat.OP_CODE, DataDynamicListFloat.read);

        // LayoutComputeOperation
        m.set(LayoutComputeOperation.OP_CODE, LayoutComputeOperation.read);

        // UpdateDynamicFloatList
        m.set(UpdateDynamicFloatList.OP_CODE, UpdateDynamicFloatList.read);

        // ImageLayout, StateLayout
        m.set(ImageLayout.OP_CODE, ImageLayout.read);
        m.set(StateLayout.OP_CODE, StateLayout.read);

        // Action operations
        m.set(HostActionOperation.OP_CODE, HostActionOperation.read);
        m.set(HostNamedActionOperation.OP_CODE, HostNamedActionOperation.read);
        m.set(ValueIntegerChangeAction.OP_CODE, ValueIntegerChangeAction.read);
        m.set(ValueStringChangeAction.OP_CODE, ValueStringChangeAction.read);
        m.set(ValueIntegerExpressionChangeAction.OP_CODE, ValueIntegerExpressionChangeAction.read);
        m.set(ValueFloatChangeAction.OP_CODE, ValueFloatChangeAction.read);

        // Additional stubs (parse-only)
        m.set(PathTween.OP_CODE, PathTween.read);
        m.set(HapticFeedback.OP_CODE, HapticFeedback.read);
        m.set(WakeIn.OP_CODE, WakeIn.read);
        m.set(TimeAttribute.OP_CODE, TimeAttribute.read);

        // New baseline ops since 2026-03-16
        m.set(Skip.OP_CODE, Skip.read);
        m.set(TextStyle.OP_CODE, TextStyle.read);

        // Sound operations (parse-only)
        m.set(SoundData.OP_CODE, SoundData.read);
        m.set(SoundExpression.OP_CODE, SoundExpression.read);
        m.set(PlaySound.OP_CODE, PlaySound.read);

        // Pattern (macro) & referenced operations (parse-only)
        m.set(ReferencedOperations.OP_CODE, ReferencedOperations.read);
        m.set(IncludeReferencedOperations.OP_CODE, IncludeReferencedOperations.read);
        m.set(PatternInflation.OP_CODE, PatternInflation.read);
        m.set(PatternBlock.OP_CODE, PatternBlock.read);
        m.set(PatternForEach.OP_CODE, PatternForEach.read);
        m.set(PatternArgument.OP_CODE, PatternArgument.read);
        m.set(PatternDefine.OP_CODE, PatternDefine.read);

        // Custom layout component (parse-only)
        m.set(Custom.OP_CODE, Custom.read);
    }

    static getOperations(): Map<number, CompanionOperationFn> {
        Operations.init();
        return Operations.sMap;
    }

    static getReader(opCode: number): CompanionOperationFn | undefined {
        Operations.init();
        return Operations.sMap.get(opCode);
    }

    static valid(opId: number): boolean {
        Operations.init();
        return Operations.sMap.has(opId);
    }
}
