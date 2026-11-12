package ee.schimke.composeai.rccreation.dwf

/**
 * Parser for Declarative Watch Face / Watch Face Format (`<WatchFace>`) XML documents (versions
 * 1–4).
 */
public object DwfParser {
  /** Fast check whether [xml] is a Watch Face Format `<WatchFace>` XML document. */
  public fun isDwfXml(xml: String): Boolean {
    if (!xml.contains("<WatchFace")) return false
    val root = XmlMiniDom.parseDocumentOrNull(xml) ?: return false
    return root.localName == "WatchFace"
  }

  /** Parses [xml] into a [DwfDocument], or returns `null` if [xml] is not a valid `<WatchFace>`. */
  public fun parseOrNull(
    xml: String,
    resources: DwfResourceResolver = DwfResourceResolver.Empty,
  ): DwfDocument? = runCatching { parse(xml, resources) }.getOrNull()

  /** Parses [xml] into a [DwfDocument], throwing [IllegalArgumentException] if malformed. */
  public fun parse(
    xml: String,
    resources: DwfResourceResolver = DwfResourceResolver.Empty,
  ): DwfDocument {
    val root = XmlMiniDom.parseDocument(xml)
    require(root.localName == "WatchFace") {
      "Expected root <WatchFace> element, got <${root.name}>"
    }
    val width = root.attr("width")?.toFloatOrNull()?.toInt()?.coerceAtLeast(1) ?: 450
    val height = root.attr("height")?.toFloatOrNull()?.toInt()?.coerceAtLeast(1) ?: 450
    val clipShape =
      when (root.attr("clipShape")?.uppercase()) {
        "RECTANGLE" -> DwfClipShape.RECTANGLE
        "NONE" -> DwfClipShape.NONE
        else -> DwfClipShape.CIRCLE
      }
    val cornerRadiusX = root.attr("cornerRadiusX")?.toFloatOrNull() ?: 0f
    val cornerRadiusY = root.attr("cornerRadiusY")?.toFloatOrNull() ?: 0f

    val metadata = LinkedHashMap<String, String>()
    for (meta in root.childElements("Metadata")) {
      val key = meta.attr("key")?.trim().orEmpty()
      val value = meta.attr("value")?.trim().orEmpty()
      if (key.isNotEmpty()) {
        metadata[key] = resolveStringIfRef(value, resources)
      }
    }

    val userConfigurations =
      root.childElements("UserConfigurations").flatMap { parseUserConfigurations(it, resources) }
    val flavorContainers =
      root.childElements("Flavors") +
        root.childElements("UserConfigurations").flatMap { it.childElements("Flavors") }
    val defaultFlavorId = flavorContainers.firstNotNullOfOrNull { it.attr("defaultValue")?.trim() }
    val flavors = flavorContainers.flatMap { parseFlavors(it, resources) }
    val bitmapFonts = root.childElements("BitmapFonts").flatMap { parseBitmapFonts(it) }
    val sceneEl = root.firstChild("Scene")
    val scene = if (sceneEl != null) parseScene(sceneEl, resources) else DwfScene()

    return DwfDocument(
      width = width,
      height = height,
      clipShape = clipShape,
      cornerRadiusX = cornerRadiusX,
      cornerRadiusY = cornerRadiusY,
      metadata = metadata,
      userConfigurations = userConfigurations,
      defaultFlavorId = defaultFlavorId,
      flavors = flavors,
      bitmapFonts = bitmapFonts,
      scene = scene,
    )
  }

  private fun parseFlavors(
    container: XmlElement,
    resources: DwfResourceResolver,
  ): List<DwfFlavor> =
    container.childElements("Flavor").mapNotNull { el ->
      val id = el.attr("id")?.trim().orEmpty()
      if (id.isEmpty()) return@mapNotNull null
      val configs = LinkedHashMap<String, String>()
      for (cfg in el.childElements("Configuration")) {
        val cfgId = cfg.attr("id")?.trim().orEmpty()
        val optId = cfg.attr("optionId")?.trim().orEmpty()
        if (cfgId.isNotEmpty() && optId.isNotEmpty()) {
          configs[cfgId] = optId
        }
      }
      val comps = LinkedHashMap<Int, DwfFlavor.ComplicationPolicy>()
      for (slot in el.childElements("ComplicationSlot")) {
        val slotId = slot.attr("slotId")?.toIntOrNull() ?: continue
        val policy = slot.firstChild("DefaultProviderPolicy")
        comps[slotId] =
          DwfFlavor.ComplicationPolicy(
            defaultSystemProvider = policy?.attr("defaultSystemProvider"),
            defaultSystemProviderType = policy?.attr("defaultSystemProviderType"),
          )
      }
      DwfFlavor(
        id = id,
        displayName = el.attr("displayName")?.let { resolveStringIfRef(it, resources) },
        icon = el.attr("icon"),
        configurations = configs,
        complications = comps,
      )
    }

  private fun parseUserConfigurations(
    container: XmlElement,
    resources: DwfResourceResolver,
  ): List<DwfUserConfiguration> =
    container.childElements().mapNotNull { el ->
      val id = el.attr("id")?.trim().orEmpty()
      if (id.isEmpty()) return@mapNotNull null
      val displayName = el.attr("displayName")?.let { resolveStringIfRef(it, resources) }
      val screenReaderText = el.attr("screenReaderText")?.let { resolveStringIfRef(it, resources) }
      when (el.localName) {
        "ColorConfiguration" -> {
          val options =
            el.childElements("ColorOption").mapNotNull { opt ->
              val optId = opt.attr("id")?.trim().orEmpty()
              if (optId.isEmpty()) return@mapNotNull null
              val rawColors =
                opt
                  .attr("colors")
                  ?.trim()
                  ?.split(Regex("\\s+"))
                  ?.filter { it.isNotEmpty() }
                  ?.mapNotNull { parseDwfColorArgb(it, resources) }
                  .orEmpty()
              val singleColor = opt.attr("color")?.let { parseDwfColorArgb(it, resources) }
              DwfUserConfiguration.ColorConfiguration.ColorOption(
                id = optId,
                displayName = opt.attr("displayName")?.let { resolveStringIfRef(it, resources) },
                colors = if (rawColors.isNotEmpty()) rawColors else listOfNotNull(singleColor),
              )
            }
          DwfUserConfiguration.ColorConfiguration(
            id = id,
            displayName = displayName,
            screenReaderText = screenReaderText,
            defaultValue = el.attr("defaultValue") ?: options.firstOrNull()?.id,
            options = options,
          )
        }
        "ListConfiguration" -> {
          val options =
            el.childElements("ListOption").mapNotNull { opt ->
              val optId = opt.attr("id")?.trim().orEmpty()
              if (optId.isEmpty()) return@mapNotNull null
              DwfUserConfiguration.ListConfiguration.ListOption(
                id = optId,
                displayName = opt.attr("displayName")?.let { resolveStringIfRef(it, resources) },
                icon = opt.attr("icon"),
              )
            }
          DwfUserConfiguration.ListConfiguration(
            id = id,
            displayName = displayName,
            screenReaderText = screenReaderText,
            defaultValue = el.attr("defaultValue") ?: options.firstOrNull()?.id,
            options = options,
          )
        }
        "BooleanConfiguration" ->
          DwfUserConfiguration.BooleanConfiguration(
            id = id,
            displayName = displayName,
            screenReaderText = screenReaderText,
            defaultValue = el.attr("defaultValue")?.equals("TRUE", ignoreCase = true) == true,
          )
        "PhotosConfiguration" ->
          DwfUserConfiguration.PhotosConfiguration(
            id = id,
            displayName = displayName,
            screenReaderText = screenReaderText,
            configType = el.attr("configType") ?: "SINGLE",
          )
        else -> null
      }
    }

  private fun parseBitmapFonts(container: XmlElement): List<DwfBitmapFont> =
    container.childElements("BitmapFont").mapNotNull { bf ->
      val name = bf.attr("name")?.trim().orEmpty()
      if (name.isEmpty()) return@mapNotNull null
      val glyphs =
        bf.childElements().mapNotNull { g ->
          when (g.localName) {
            "Character",
            "Word" -> {
              val text = g.attr("name") ?: return@mapNotNull null
              val res = g.attr("resource") ?: return@mapNotNull null
              val w = g.attr("width")?.toFloatOrNull() ?: 0f
              val h = g.attr("height")?.toFloatOrNull() ?: 0f
              DwfBitmapFont.Glyph(text = text, resource = res, width = w, height = h)
            }
            else -> null
          }
        }
      DwfBitmapFont(name = name, glyphs = glyphs)
    }

  private fun parseScene(sceneEl: XmlElement, resources: DwfResourceResolver): DwfScene =
    DwfScene(
      backgroundColor = sceneEl.attr("backgroundColor") ?: "#FF000000",
      transforms = parseTransforms(sceneEl),
      variants = parseVariants(sceneEl),
      children = parseSceneNodes(sceneEl, resources),
    )

  private fun parseSceneNodes(
    parent: XmlElement,
    resources: DwfResourceResolver,
  ): List<DwfSceneNode> = parent.childElements().mapNotNull { parseSceneNode(it, resources) }

  private fun parseSceneNode(el: XmlElement, resources: DwfResourceResolver): DwfSceneNode? =
    when (el.localName) {
      "Group" ->
        DwfSceneNode.Group(
          id = el.attr("id") ?: el.attr("name"),
          geometry = parseGeometry(el),
          children = parseSceneNodes(el, resources),
        )
      "PartDraw" ->
        DwfSceneNode.PartDraw(
          id = el.attr("id") ?: el.attr("name"),
          geometry = parseGeometry(el),
          shapes = parseDrawShapes(el),
        )
      "PartText" -> {
        val textEl = el.firstChild("Text")
        val textCircularEl = el.firstChild("TextCircular")
        val formatterFallback =
          if (textEl == null && textCircularEl == null) {
            // Some minimal watch faces put <Font> or <BitmapFont> directly inside <PartText>
            val directFont = el.firstChild("Font")
            val directBitmapFont = el.firstChild("BitmapFont")
            if (directFont != null || directBitmapFont != null) {
              parseTextSpec(el, resources)
            } else {
              null
            }
          } else {
            textEl?.let { parseTextSpec(it, resources) }
          }
        DwfSceneNode.PartText(
          id = el.attr("id") ?: el.attr("name"),
          geometry = parseGeometry(el),
          text = formatterFallback,
          textCircular = textCircularEl?.let { parseTextCircularSpec(it, resources) },
        )
      }
      "PartImage" -> {
        val directImage =
          el.firstChild("Image")?.attr("resource")
            ?: el.firstChild("Photos")?.attr("defaultImageResource")
            ?: el.attr("resource")
        val choices =
          el
            .firstChild("Images")
            ?.childElements("Image")
            ?.mapNotNull { it.attr("resource") }
            .orEmpty()
        val filters = el.firstChild("ImageFilters")?.childElements()?.map { it.localName }.orEmpty()
        DwfSceneNode.PartImage(
          id = el.attr("id") ?: el.attr("name"),
          geometry = parseGeometry(el),
          resource = directImage ?: choices.firstOrNull(),
          imageFilters = filters,
          resourceChoices = choices,
        )
      }
      "PartAnimatedImage" -> {
        val animRes =
          el.firstChild("AnimatedImages")?.firstChild("AnimatedImage")?.attr("resource")
            ?: el.firstChild("AnimatedImage")?.attr("resource")
            ?: el
              .firstChild("SequenceImages")
              ?.childElements("Image")
              ?.firstOrNull()
              ?.attr("resource")
        val format =
          el.firstChild("AnimatedImages")?.firstChild("AnimatedImage")?.attr("format") ?: "IMAGE"
        val thumb = el.firstChild("Thumbnail")?.attr("resource") ?: el.attr("thumbnail")
        DwfSceneNode.PartAnimatedImage(
          id = el.attr("id") ?: el.attr("name"),
          geometry = parseGeometry(el),
          resource = animRes,
          thumbnail = thumb,
          format = format,
        )
      }
      "AnalogClock" -> {
        val hands =
          el.childElements().mapNotNull { child ->
            when (child.localName) {
              "HourHand" -> parseClockHand(child, DwfClockHand.HandKind.HOUR, resources)
              "MinuteHand" -> parseClockHand(child, DwfClockHand.HandKind.MINUTE, resources)
              "SecondHand" -> parseClockHand(child, DwfClockHand.HandKind.SECOND, resources)
              else -> null
            }
          }
        val extraChildren =
          el
            .childElements()
            .filter { it.localName !in setOf("HourHand", "MinuteHand", "SecondHand") }
            .mapNotNull { parseSceneNode(it, resources) }
        DwfSceneNode.AnalogClock(
          id = el.attr("id") ?: el.attr("name"),
          geometry = parseGeometry(el),
          hands = hands,
          children = extraChildren,
        )
      }
      "DigitalClock" -> {
        val timeTexts = el.childElements("TimeText").map { parseTimeText(it, resources) }
        DwfSceneNode.DigitalClock(
          id = el.attr("id") ?: el.attr("name"),
          geometry = parseGeometry(el),
          timeTexts = timeTexts,
        )
      }
      "Condition" -> parseCondition(el, resources)
      "ListConfiguration" -> {
        val id = el.attr("id")?.trim().orEmpty()
        val options =
          el.childElements("ListOption").mapNotNull { opt ->
            val optId = opt.attr("id")?.trim().orEmpty()
            if (optId.isEmpty()) null
            else
              DwfSceneNode.ListConfigurationSwitch.OptionBranch(
                id = optId,
                children = parseSceneNodes(opt, resources),
              )
          }
        DwfSceneNode.ListConfigurationSwitch(id = id, options = options)
      }
      "BooleanConfiguration" -> {
        val id = el.attr("id")?.trim().orEmpty()
        val trueNodes =
          el
            .childElements("BooleanOption")
            .filter { it.attr("id")?.equals("TRUE", ignoreCase = true) == true }
            .flatMap { parseSceneNodes(it, resources) }
        val falseNodes =
          el
            .childElements("BooleanOption")
            .filter { it.attr("id")?.equals("FALSE", ignoreCase = true) == true }
            .flatMap { parseSceneNodes(it, resources) }
        DwfSceneNode.BooleanConfigurationSwitch(
          id = id,
          trueChildren = trueNodes,
          falseChildren = falseNodes,
        )
      }
      "ComplicationSlot" -> parseComplicationSlot(el, resources)
      "Reference" ->
        DwfSceneNode.Reference(
          name = el.attr("name").orEmpty(),
          source = el.attr("source").orEmpty(),
          geometry = parseGeometry(el),
        )
      else -> null
    }

  private fun parseClockHand(
    el: XmlElement,
    kind: DwfClockHand.HandKind,
    resources: DwfResourceResolver,
  ): DwfClockHand {
    val sweepEl = el.firstChild("Sweep")
    val freq = sweepEl?.attr("frequency")?.toIntOrNull()
    val res = el.attr("resource") ?: el.firstChild("Image")?.attr("resource")
    return DwfClockHand(
      kind = kind,
      resource = res,
      geometry = parseGeometry(el),
      sweepFrequencyHz = freq,
      children = parseSceneNodes(el, resources),
    )
  }

  private fun parseTimeText(el: XmlElement, resources: DwfResourceResolver): DwfTimeText {
    val format = el.attr("format") ?: "HH:mm"
    val hourFormat =
      when (el.attr("hourFormat")?.uppercase()) {
        "12",
        "TWELVE" -> DwfHourFormat.TWELVE
        "24",
        "TWENTY_FOUR" -> DwfHourFormat.TWENTY_FOUR
        else -> DwfHourFormat.SYNC_TO_DEVICE
      }
    val align = parseHorizontalAlign(el.attr("align"), default = DwfHorizontalAlign.START)
    val childFont = el.firstChild("Font")
    val childBitmapFont = el.firstChild("BitmapFont")
    val fontSpec =
      if (childFont != null) {
        parseFontSpec(childFont, resources)
      } else {
        // Attributes directly on <TimeText> when no <Font> child is used (e.g. BitmapFont or
        // compact syntax)
        parseFontSpec(el, resources)
      }
    return DwfTimeText(
      format = format,
      hourFormat = hourFormat,
      align = align,
      geometry = parseGeometry(el),
      font = fontSpec,
      bitmapFont = childBitmapFont?.let { parseBitmapFontUsage(it, resources) },
    )
  }

  private fun parseTextSpec(el: XmlElement, resources: DwfResourceResolver): DwfTextSpec {
    val align = parseHorizontalAlign(el.attr("align"), default = DwfHorizontalAlign.CENTER)
    val ellipsis = el.attr("ellipsis")?.equals("TRUE", ignoreCase = true) == true
    val maxLines = el.attr("maxLines")?.toIntOrNull()?.coerceAtLeast(1) ?: Int.MAX_VALUE
    val fontEl = el.firstChild("Font")
    val bitmapFontEl = el.firstChild("BitmapFont")
    val fontSpec = fontEl?.let { parseFontSpec(it, resources) }
    val bitmapUsage = bitmapFontEl?.let { parseBitmapFontUsage(it, resources) }
    val elements =
      when {
        fontSpec != null && fontSpec.elements.isNotEmpty() -> fontSpec.elements
        bitmapUsage != null && bitmapUsage.elements.isNotEmpty() -> bitmapUsage.elements
        else -> parseTextElements(el, resources)
      }
    return DwfTextSpec(
      align = align,
      ellipsis = ellipsis,
      maxLines = maxLines,
      elements = elements,
      font = fontSpec,
      bitmapFont = bitmapUsage,
    )
  }

  private fun parseTextCircularSpec(
    el: XmlElement,
    resources: DwfResourceResolver,
  ): DwfTextCircularSpec {
    val cx = el.attr("centerX")?.toFloatOrNull() ?: 0f
    val cy = el.attr("centerY")?.toFloatOrNull() ?: 0f
    val w = el.attr("width")?.toFloatOrNull() ?: (cx * 2f)
    val h = el.attr("height")?.toFloatOrNull() ?: (cy * 2f)
    val startAngle = el.attr("startAngle")?.toFloatOrNull() ?: 0f
    val endAngle = el.attr("endAngle")?.toFloatOrNull() ?: 360f
    val direction = parseArcDirection(el.attr("direction"))
    val align = parseHorizontalAlign(el.attr("align"), default = DwfHorizontalAlign.CENTER)
    val ellipsis = el.attr("ellipsis")?.equals("TRUE", ignoreCase = true) == true
    val fontEl = el.firstChild("Font")
    val bitmapFontEl = el.firstChild("BitmapFont")
    val fontSpec = fontEl?.let { parseFontSpec(it, resources) }
    val bitmapUsage = bitmapFontEl?.let { parseBitmapFontUsage(it, resources) }
    val elements =
      when {
        fontSpec != null && fontSpec.elements.isNotEmpty() -> fontSpec.elements
        bitmapUsage != null && bitmapUsage.elements.isNotEmpty() -> bitmapUsage.elements
        else -> parseTextElements(el, resources)
      }
    return DwfTextCircularSpec(
      centerX = cx,
      centerY = cy,
      width = w,
      height = h,
      startAngle = startAngle,
      endAngle = endAngle,
      direction = direction,
      align = align,
      ellipsis = ellipsis,
      transforms = parseTransforms(el),
      variants = parseVariants(el),
      elements = elements,
      font = fontSpec,
      bitmapFont = bitmapUsage,
    )
  }

  private fun parseFontSpec(el: XmlElement, resources: DwfResourceResolver): DwfFontSpec {
    val outlineEl = el.firstChild("Outline")
    return DwfFontSpec(
      family = el.attr("family") ?: "SYNC_TO_DEVICE",
      size = el.attr("size")?.toFloatOrNull() ?: 24f,
      color = el.attr("color") ?: "#FFFFFFFF",
      weight = el.attr("weight") ?: "NORMAL",
      slant = el.attr("slant") ?: "NORMAL",
      width = el.attr("width") ?: "NORMAL",
      letterSpacing = el.attr("letterSpacing")?.toFloatOrNull() ?: 0f,
      outlineColor = outlineEl?.attr("color"),
      outlineWidth = outlineEl?.attr("width")?.toFloatOrNull() ?: 0f,
      transforms = parseTransforms(el),
      variants = parseVariants(el),
      elements = parseTextElements(el, resources),
    )
  }

  private fun parseBitmapFontUsage(
    el: XmlElement,
    resources: DwfResourceResolver,
  ): DwfBitmapFontUsage =
    DwfBitmapFontUsage(
      family = el.attr("family").orEmpty(),
      size = el.attr("size")?.toFloatOrNull() ?: 24f,
      color = el.attr("color"),
      elements = parseTextElements(el, resources),
    )

  private fun parseTextElements(
    container: XmlElement,
    resources: DwfResourceResolver,
  ): List<DwfTextElement> {
    val result = ArrayList<DwfTextElement>()
    for (node in container.children) {
      when (node) {
        is XmlText -> {
          val normalized = normalizeXmlInlineText(node.text, container.children.size == 1)
          if (normalized.isNotEmpty()) {
            result += DwfTextElement.Literal(resolveStringIfRef(normalized, resources))
          }
        }
        is XmlElement -> {
          when (node.localName) {
            "Transform",
            "Variant" -> Unit
            "Shadow",
            "Outline",
            "OutGlow",
            "Underline",
            "StrikeThrough" -> {
              result += parseTextElements(node, resources)
            }
            "Parameter" -> {
              val expr = node.attr("expression")?.trim().orEmpty()
              if (expr.isNotEmpty()) {
                result += DwfTextElement.Parameter(expr)
              }
            }
            "Template" -> {
              val rawFormat =
                node.children
                  .filterIsInstance<XmlText>()
                  .joinToString("") { it.text }
                  .trim()
                  .let { resolveStringIfRef(it, resources) }
              val params =
                node.childElements("Parameter").mapNotNull {
                  it.attr("expression")?.trim()?.takeIf(String::isNotEmpty)
                }
              result += DwfTextElement.Template(format = rawFormat, parameters = params)
            }
            "Upper" -> result += DwfTextElement.Upper(parseTextElements(node, resources))
            "Lower" -> result += DwfTextElement.Lower(parseTextElements(node, resources))
            "Span" ->
              result +=
                DwfTextElement.Span(
                  font = parseFontSpec(node, resources),
                  elements = parseTextElements(node, resources),
                )
            "Font" -> {
              // When <Text> contains <Font> directly and parseTextElements was called on <Text>
              result += parseTextElements(node, resources)
            }
            else -> {
              val inner = node.textContent().trim()
              if (inner.isNotEmpty()) {
                result += DwfTextElement.Literal(resolveStringIfRef(inner, resources))
              }
            }
          }
        }
      }
    }
    return result
  }

  private fun normalizeXmlInlineText(raw: String, singleChild: Boolean): String {
    if (raw.isBlank()) {
      return if (singleChild && raw.isNotEmpty() && '\n' !in raw && '\r' !in raw) raw else ""
    }
    if ('\n' !in raw && '\r' !in raw) return raw
    return raw.replace(Regex("\\s+"), " ").trim()
  }

  private fun parseCondition(
    el: XmlElement,
    resources: DwfResourceResolver,
  ): DwfSceneNode.Condition {
    val exprMap = LinkedHashMap<String, String>()
    for (exprGroup in el.childElements("Expressions")) {
      for (exprEl in exprGroup.childElements("Expression")) {
        val name = exprEl.attr("name")?.trim().orEmpty()
        val body = exprEl.textContent().trim()
        if (name.isNotEmpty() && body.isNotEmpty()) {
          exprMap[name] = body
        }
      }
    }
    val branches = ArrayList<DwfSceneNode.Condition.Branch>()
    for (child in el.childElements()) {
      when (child.localName) {
        "Compare" -> {
          val exprRef = child.attr("expression")?.trim()
          val resolvedExpr = exprRef?.let { exprMap[it] ?: it }
          branches +=
            DwfSceneNode.Condition.Branch(
              expression = resolvedExpr,
              children = parseSceneNodes(child, resources),
            )
        }
        "Default" ->
          branches +=
            DwfSceneNode.Condition.Branch(
              expression = null,
              children = parseSceneNodes(child, resources),
            )
      }
    }
    return DwfSceneNode.Condition(branches = branches, expressions = exprMap)
  }

  private fun parseComplicationSlot(
    el: XmlElement,
    resources: DwfResourceResolver,
  ): DwfSceneNode.ComplicationSlot {
    val slotId = el.attr("slotId")?.toIntOrNull() ?: 0
    val supported =
      el
        .attr("supportedTypes")
        ?.trim()
        ?.split(Regex("[\\s,]+"))
        ?.filter { it.isNotEmpty() }
        .orEmpty()
    val policy = el.firstChild("DefaultProviderPolicy")
    val defaultSystemProvider = policy?.attr("defaultSystemProvider")
    val defaultSystemProviderType = policy?.attr("defaultSystemProviderType")
    val bounding =
      el
        .childElements()
        .firstOrNull {
          it.localName in setOf("BoundingBox", "BoundingOval", "BoundingRoundBox", "BoundingArc")
        }
        ?.let { b ->
          DwfBoundingShape(
            kind = b.localName,
            x = b.attr("x")?.toFloatOrNull() ?: 0f,
            y = b.attr("y")?.toFloatOrNull() ?: 0f,
            width = b.attr("width")?.toFloatOrNull() ?: 0f,
            height = b.attr("height")?.toFloatOrNull() ?: 0f,
            outlinePadding = b.attr("outlinePadding")?.toFloatOrNull() ?: 0f,
          )
        }
    val variants =
      el.childElements("Complication").mapNotNull { comp ->
        val type = comp.attr("type")?.trim().orEmpty()
        if (type.isEmpty()) null
        else
          DwfSceneNode.ComplicationSlot.ComplicationVariant(
            type = type,
            children = parseSceneNodes(comp, resources),
          )
      }
    return DwfSceneNode.ComplicationSlot(
      slotId = slotId,
      geometry = parseGeometry(el),
      supportedTypes = supported,
      isCustomizable = el.attr("isCustomizable")?.equals("FALSE", ignoreCase = true) != true,
      defaultSystemProvider = defaultSystemProvider,
      defaultSystemProviderType = defaultSystemProviderType,
      boundingShape = bounding,
      complications = variants,
    )
  }

  private fun parseDrawShapes(partDraw: XmlElement): List<DwfDrawShape> {
    val shapes = ArrayList<DwfDrawShape>()
    for (child in partDraw.childElements()) {
      val primitive = parseDrawPrimitive(child) ?: continue
      val fills = child.childElements("Fill").map(::parseFill)
      val strokes = child.childElements("Stroke").map(::parseStroke)
      val weightedStrokes = child.childElements("WeightedStroke").map(::parseWeightedStroke)
      val weighted =
        child.childElements("WeightedElement").map { w ->
          DwfWeightedElement(
            weight = w.attr("weight")?.toFloatOrNull() ?: 1f,
            color = w.attr("color") ?: "#FFFFFFFF",
            transforms = parseTransforms(w),
          )
        }
      val childHasStyle =
        fills.isNotEmpty() ||
          strokes.isNotEmpty() ||
          weighted.isNotEmpty() ||
          weightedStrokes.isNotEmpty()
      // Also support <Fill> / <Stroke> placed as siblings on <PartDraw> if child has none
      val effectiveFills =
        if (!childHasStyle) {
          partDraw.childElements("Fill").map(::parseFill)
        } else {
          fills
        }
      val effectiveStrokes =
        if (!childHasStyle) {
          partDraw.childElements("Stroke").map(::parseStroke)
        } else {
          strokes
        }
      shapes +=
        DwfDrawShape(
          primitive = primitive,
          fills = effectiveFills,
          strokes = effectiveStrokes,
          weightedElements = weighted,
          weightedStrokes = weightedStrokes,
        )
    }
    return shapes
  }

  private fun parseWeightedStroke(el: XmlElement): DwfWeightedStroke {
    val cap =
      when (el.attr("cap")?.uppercase()) {
        "ROUND" -> DwfStrokeCap.ROUND
        "SQUARE" -> DwfStrokeCap.SQUARE
        else -> DwfStrokeCap.BUTT
      }
    return DwfWeightedStroke(
      colors = el.attr("colors") ?: "#FFFFFFFF",
      weights = el.attr("weights"),
      thickness = el.attr("thickness")?.toFloatOrNull() ?: 1f,
      cap = cap,
      interpolate = el.attr("interpolate"),
      transforms = parseTransforms(el),
      variants = parseVariants(el),
    )
  }

  private fun parseDrawPrimitive(el: XmlElement): DwfDrawPrimitive? =
    when (el.localName) {
      "Line" ->
        DwfDrawPrimitive.Line(
          startX = el.attr("startX")?.toFloatOrNull() ?: 0f,
          startY = el.attr("startY")?.toFloatOrNull() ?: 0f,
          endX = el.attr("endX")?.toFloatOrNull() ?: 0f,
          endY = el.attr("endY")?.toFloatOrNull() ?: 0f,
          transforms = parseTransforms(el),
          variants = parseVariants(el),
        )
      "Arc" ->
        DwfDrawPrimitive.Arc(
          centerX = el.attr("centerX")?.toFloatOrNull() ?: 0f,
          centerY = el.attr("centerY")?.toFloatOrNull() ?: 0f,
          width = el.attr("width")?.toFloatOrNull() ?: 0f,
          height = el.attr("height")?.toFloatOrNull() ?: 0f,
          startAngle = el.attr("startAngle")?.toFloatOrNull() ?: 0f,
          endAngle = el.attr("endAngle")?.toFloatOrNull() ?: 0f,
          direction = parseArcDirection(el.attr("direction")),
          transforms = parseTransforms(el),
          variants = parseVariants(el),
        )
      "Rectangle" ->
        DwfDrawPrimitive.Rectangle(
          x = el.attr("x")?.toFloatOrNull() ?: 0f,
          y = el.attr("y")?.toFloatOrNull() ?: 0f,
          width = el.attr("width")?.toFloatOrNull() ?: 0f,
          height = el.attr("height")?.toFloatOrNull() ?: 0f,
          transforms = parseTransforms(el),
          variants = parseVariants(el),
        )
      "RoundRectangle" ->
        DwfDrawPrimitive.RoundRectangle(
          x = el.attr("x")?.toFloatOrNull() ?: 0f,
          y = el.attr("y")?.toFloatOrNull() ?: 0f,
          width = el.attr("width")?.toFloatOrNull() ?: 0f,
          height = el.attr("height")?.toFloatOrNull() ?: 0f,
          cornerRadiusX = el.attr("cornerRadiusX")?.toFloatOrNull() ?: 0f,
          cornerRadiusY =
            el.attr("cornerRadiusY")?.toFloatOrNull()
              ?: el.attr("cornerRadiusX")?.toFloatOrNull()
              ?: 0f,
          transforms = parseTransforms(el),
          variants = parseVariants(el),
        )
      "Ellipse" ->
        DwfDrawPrimitive.Ellipse(
          x = el.attr("x")?.toFloatOrNull() ?: 0f,
          y = el.attr("y")?.toFloatOrNull() ?: 0f,
          width = el.attr("width")?.toFloatOrNull() ?: 0f,
          height = el.attr("height")?.toFloatOrNull() ?: 0f,
          transforms = parseTransforms(el),
          variants = parseVariants(el),
        )
      else -> null
    }

  private fun parseFill(el: XmlElement): DwfFill =
    DwfFill(
      color = el.attr("color") ?: "#FFFFFFFF",
      gradient = parseGradientChild(el),
      transforms = parseTransforms(el),
      variants = parseVariants(el),
    )

  private fun parseStroke(el: XmlElement): DwfStroke {
    val cap =
      when (el.attr("cap")?.uppercase()) {
        "ROUND" -> DwfStrokeCap.ROUND
        "SQUARE" -> DwfStrokeCap.SQUARE
        else -> DwfStrokeCap.BUTT
      }
    val dashes =
      el
        .attr("dashIntervals")
        ?.trim()
        ?.split(Regex("\\s+"))
        ?.mapNotNull { it.toFloatOrNull() }
        .orEmpty()
    return DwfStroke(
      color = el.attr("color") ?: "#FFFFFFFF",
      thickness = el.attr("thickness")?.toFloatOrNull() ?: 1f,
      cap = cap,
      dashIntervals = dashes,
      gradient = parseGradientChild(el),
      transforms = parseTransforms(el),
      variants = parseVariants(el),
    )
  }

  private fun parseGradientChild(parent: XmlElement): DwfGradient? {
    for (child in parent.childElements()) {
      when (child.localName) {
        "LinearGradient" ->
          return DwfGradient.Linear(
            startX = child.attr("startX")?.toFloatOrNull() ?: 0f,
            startY = child.attr("startY")?.toFloatOrNull() ?: 0f,
            endX = child.attr("endX")?.toFloatOrNull() ?: 0f,
            endY = child.attr("endY")?.toFloatOrNull() ?: 0f,
            colors = parseSpaceList(child.attr("colors")),
            positions = parseFloatSpaceList(child.attr("positions")),
            transforms = parseTransforms(child),
          )
        "RadialGradient" ->
          return DwfGradient.Radial(
            centerX = child.attr("centerX")?.toFloatOrNull() ?: 0f,
            centerY = child.attr("centerY")?.toFloatOrNull() ?: 0f,
            radius = child.attr("radius")?.toFloatOrNull() ?: 1f,
            colors = parseSpaceList(child.attr("colors")),
            positions = parseFloatSpaceList(child.attr("positions")),
            transforms = parseTransforms(child),
          )
        "SweepGradient" ->
          return DwfGradient.Sweep(
            centerX = child.attr("centerX")?.toFloatOrNull() ?: 0f,
            centerY = child.attr("centerY")?.toFloatOrNull() ?: 0f,
            startAngle = child.attr("startAngle")?.toFloatOrNull() ?: 0f,
            endAngle = child.attr("endAngle")?.toFloatOrNull() ?: 360f,
            direction = parseArcDirection(child.attr("direction")),
            colors = parseSpaceList(child.attr("colors")),
            positions = parseFloatSpaceList(child.attr("positions")),
            transforms = parseTransforms(child),
          )
      }
    }
    return null
  }

  private fun parseGeometry(el: XmlElement): DwfNodeGeometry {
    val renderMode =
      when (el.attr("renderMode")?.uppercase()) {
        "MASK" -> DwfRenderMode.MASK
        "ALL" -> DwfRenderMode.ALL
        else -> DwfRenderMode.SOURCE
      }
    val gyroEl = el.firstChild("Gyro")
    val gyro = gyroEl?.let {
      DwfGyro(
        x = it.attr("x")?.toFloatOrNull() ?: 0f,
        y = it.attr("y")?.toFloatOrNull() ?: 0f,
        scaleX = it.attr("scaleX")?.toFloatOrNull() ?: 0f,
        scaleY = it.attr("scaleY")?.toFloatOrNull() ?: 0f,
        angle = it.attr("angle")?.toFloatOrNull() ?: 0f,
        alpha = it.attr("alpha")?.toFloatOrNull() ?: 0f,
      )
    }
    val launch =
      el.firstChild("Launch")?.attr("target")?.takeIf { it.isNotBlank() }?.let(::DwfLaunch)
    return DwfNodeGeometry(
      name = el.attr("name"),
      x = el.attr("x")?.toFloatOrNull() ?: 0f,
      y = el.attr("y")?.toFloatOrNull() ?: 0f,
      width = el.attr("width")?.toFloatOrNull() ?: 0f,
      height = el.attr("height")?.toFloatOrNull() ?: 0f,
      pivotX = el.attr("pivotX")?.toFloatOrNull() ?: 0.5f,
      pivotY = el.attr("pivotY")?.toFloatOrNull() ?: 0.5f,
      angle = el.attr("angle")?.toFloatOrNull() ?: 0f,
      scaleX = el.attr("scaleX")?.toFloatOrNull() ?: 1f,
      scaleY = el.attr("scaleY")?.toFloatOrNull() ?: 1f,
      alpha = el.attr("alpha")?.toFloatOrNull()?.toInt()?.coerceIn(0, 255) ?: 255,
      tintColor = el.attr("tintColor"),
      renderMode = renderMode,
      transforms = parseTransforms(el),
      variants = parseVariants(el),
      gyro = gyro,
      launch = launch,
    )
  }

  private fun parseTransforms(el: XmlElement): List<DwfTransform> =
    el.childElements("Transform").mapNotNull { t ->
      val target = t.attr("target")?.trim().orEmpty()
      val value = t.attr("value")?.trim().orEmpty()
      if (target.isEmpty() || value.isEmpty()) null
      else DwfTransform(target = target, value = value, mode = t.attr("mode") ?: "TO")
    }

  private fun parseVariants(el: XmlElement): List<DwfVariant> =
    el.childElements("Variant").mapNotNull { v ->
      val mode = v.attr("mode")?.trim().orEmpty()
      val target = v.attr("target")?.trim().orEmpty()
      val value = v.attr("value")?.trim().orEmpty()
      if (mode.isEmpty() || target.isEmpty() || value.isEmpty()) null
      else DwfVariant(mode = mode, target = target, value = value)
    }

  private fun parseHorizontalAlign(raw: String?, default: DwfHorizontalAlign): DwfHorizontalAlign =
    when (raw?.uppercase()) {
      "START",
      "LEFT" -> DwfHorizontalAlign.START
      "CENTER" -> DwfHorizontalAlign.CENTER
      "END",
      "RIGHT" -> DwfHorizontalAlign.END
      else -> default
    }

  private fun parseArcDirection(raw: String?): DwfArcDirection =
    when (raw?.uppercase()) {
      "COUNTER_CLOCKWISE" -> DwfArcDirection.COUNTER_CLOCKWISE
      else -> DwfArcDirection.CLOCKWISE
    }

  private fun parseSpaceList(raw: String?): List<String> =
    raw?.trim()?.split(Regex("\\s+"))?.filter { it.isNotEmpty() }.orEmpty()

  private fun parseFloatSpaceList(raw: String?): List<Float>? =
    raw?.trim()?.split(Regex("\\s+"))?.mapNotNull { it.toFloatOrNull() }?.takeIf { it.isNotEmpty() }

  private fun resolveStringIfRef(raw: String, resources: DwfResourceResolver): String {
    if (raw.startsWith("@string/")) {
      return resources.resolveString(raw) ?: raw.removePrefix("@string/")
    }
    if (raw.isNotEmpty() && raw.all { it.isLetterOrDigit() || it == '_' }) {
      resources.resolveString(raw)?.let {
        return it
      }
    }
    return raw
  }
}
