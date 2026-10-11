package ee.schimke.composeai.guidelines

import ee.schimke.composeai.guidelines.protocol.GuidelineRequestV1
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * A model that is not called: each request is written to [directory] for someone else to judge (the
 * agent that ran `compose-preview review`, or a subagent it hands the files to), and the answer is
 * read back on a later run. The engine, its batching, its evidence rounds, its reply checks and its
 * cache are the same as for a model it calls.
 *
 * A request is named by a hash of everything sent ([OpenRouterClient.chatBody], pictures included),
 * so the same previews, rules and evidence make the same request on every run:
 * ```
 * <directory>/<id>/request.md     the prompt, the picture paths, the reply schema
 * <directory>/<id>/picture-N.png  picture N, as the reply's `regions[].picture` numbers it
 * <directory>/<id>/reply.json     written by whoever judges it
 * ```
 *
 * Without `reply.json` the answer is [ModelResponse.DEFERRED], and the previews stay pending. With
 * it, the reply goes through the same checks as a model's, and a round that needs more evidence
 * writes the follow-up request the same way. Answered requests stay until [prune].
 *
 * [modelName] is what the record names as the model that answered, and part of the cache key.
 */
public class DeferredReplyModel(
  private val directory: File,
  private val modelName: String = DEFAULT_MODEL,
  /** The command that reads the replies back, named in each `request.md`. */
  private val rerun: String = "compose-preview review",
) : GuidelineModel {
  private val lock = Any()
  private val pending = linkedSetOf<File>()
  private val answered = linkedSetOf<File>()

  /** The `request.md` files of requests this run left waiting for a reply. */
  public val waiting: List<File>
    get() = synchronized(lock) { pending.toList() }

  override fun complete(request: GuidelineRequestV1, model: String): ModelResponse {
    val dir = File(directory, idOf(request))
    val reply = File(dir, REPLY)
    if (reply.isFile) {
      synchronized(lock) { answered += dir }
      return ModelResponse(200, completion(reply.readText()))
    }
    write(dir, request)
    val markdown = File(dir, REQUEST)
    synchronized(lock) { pending += markdown }
    return ModelResponse.deferred("waiting for a reply: ${markdown.path}")
  }

  /**
   * Deletes request directories this run neither answered from nor wrote, so [directory] holds only
   * what the current previews ask. Call after a run over the whole selection.
   */
  public fun prune() {
    val keep = synchronized(lock) { answered + pending.map { it.parentFile } }
    directory
      .listFiles()
      .orEmpty()
      .filter { it.isDirectory && ID.matches(it.name) && it !in keep }
      .forEach { it.deleteRecursively() }
  }

  private fun write(dir: File, request: GuidelineRequestV1) {
    dir.mkdirs()
    val pictures =
      request.pictures.mapIndexedNotNull { index, picture ->
        val data = picture.dataUrl?.substringAfter("base64,", "") ?: return@mapIndexedNotNull null
        val file = File(dir, "picture-${index + 1}.png")
        if (!file.isFile) file.writeBytes(Base64.getDecoder().decode(data))
        Triple(index + 1, file, picture.description)
      }
    val schema = PRETTY.encodeToString(JsonObject.serializer(), request.responseSchema)
    File(dir, REQUEST)
      .writeText(
        buildString {
          append("# Design guideline review request\n\n")
          append("Judge this request as the model would, then write ONLY the JSON reply to\n")
          append("`").append(File(dir, REPLY).path).append("`\n")
          append("and run `").append(rerun).append("` again with the same selection. ")
          append("Look at every picture listed below.\n\n")
          append("## Instructions\n\n").append(request.systemPrompt).append("\n\n")
          append("## Request\n\n").append(request.userText).append("\n\n")
          append("## Pictures\n\n")
          if (pictures.isEmpty()) append("None.\n")
          pictures.forEach { (number, file, description) ->
            append(number).append(". `").append(file.path).append("`")
            description.takeIf { it.isNotBlank() }?.let { append(" — ").append(it) }
            append('\n')
          }
          append("\n## Reply schema\n\n```json\n").append(schema).append("\n```\n")
        }
      )
  }

  /** [reply] as the chat completion [GuidelineResponse.parse] reads. */
  private fun completion(reply: String): String = buildJsonObject {
    put("model", modelName)
    put(
      "choices",
      buildJsonArray {
        add(
          buildJsonObject {
            put("finish_reason", "stop")
            putJsonObject("message") {
              put("role", "assistant")
              put("content", JsonPrimitive(reply))
            }
          }
        )
      },
    )
  }
    .toString()

  public companion object {
    /** What the record names as the model when the caller names none. */
    public const val DEFAULT_MODEL: String = "agent"

    public const val REQUEST: String = "request.md"
    public const val REPLY: String = "reply.json"

    private val ID = Regex("[0-9a-f]{16}")
    private val PRETTY = Json { prettyPrint = true }

    /** The stable name of [request]: a hash of everything a model would be sent. */
    public fun idOf(request: GuidelineRequestV1): String {
      val body = OpenRouterClient.chatBody(request, "", reasoningEffort = null).toString()
      return MessageDigest.getInstance("SHA-256")
        .digest(body.toByteArray())
        .joinToString("") { "%02x".format(it) }
        .take(16)
    }
  }
}
