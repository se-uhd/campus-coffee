package de.seuhd.campuscoffee.data.persistence.eventsourcing

import com.fasterxml.jackson.annotation.JsonIgnore
import de.seuhd.campuscoffee.domain.model.objects.Pos
import de.seuhd.campuscoffee.domain.model.objects.Review
import de.seuhd.campuscoffee.domain.model.objects.User
import tools.jackson.core.JsonGenerator
import tools.jackson.databind.SerializationContext
import tools.jackson.databind.ValueSerializer
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.module.SimpleModule
import tools.jackson.module.kotlin.KotlinModule

/**
 * The Jackson mapper for the event log. The same mapper builds the event [body] map and, via Hibernate's
 * `hibernate.type.json_format_mapper`, serializes that map into the `jsonb` column and reads it back, so a
 * value stored in a body reads back as the same value. It uses Jackson version 3 (the major Spring
 * Framework 7 ships), which renders `java.time` as ISO-8601 strings by default, so the timestamps survive
 * unchanged without extra configuration.
 *
 * The Kotlin module is registered because the domain models are immutable `data class`es: on a rebuild the
 * projector reconstructs them from a body, which needs Kotlin's primary-constructor binding.
 *
 * The event body records domain state, not read-model bookkeeping or secrets:
 * - the raw [User.password] is always dropped. It is only an input (the client sends it, the domain hashes
 *   it into [User.passwordHash] and discards it), so it is never stored and must never reach an event.
 * - the optimistic-locking version ([Pos.version], [User.version], and a review's) is dropped. It is a
 *   read-model counter the projection derives, so storing it in the append-only log would record a value
 *   that a rebuild reconstructs anyway.
 * - a [Review] is flattened to its POS and author ids, so a review event records references rather than
 *   copies of the POS and the author (a copy would leak the author's `passwordHash`). A `User` event does
 *   keep `passwordHash`, so a login still works after a rebuild from the log.
 *
 * It is a singleton rather than a Spring `ObjectMapper` bean, with its event-specific serializers and
 * mixins kept off the application's general-purpose mapper.
 */
object EventJsonMapper {
    val instance: JsonMapper = build()

    /**
     * Builds the event mapper: the Kotlin module, the serializer that flattens a review to ids, and the
     * mixins that drop the raw password and the read-model version.
     */
    private fun build(): JsonMapper =
        JsonMapper
            .builder()
            .addModule(KotlinModule.Builder().build())
            .addModule(SimpleModule().addSerializer(Review::class.java, ReviewEventSerializer()))
            .addMixIn(Pos::class.java, PosEventMixin::class.java)
            .addMixIn(User::class.java, UserEventMixin::class.java)
            .build()

    /** Drops the read-model [Pos.version] from a serialized POS, keeping it out of the event body. */
    @Suppress("unused")
    private abstract class PosEventMixin {
        @get:JsonIgnore
        abstract val version: Long?
    }

    /** Drops the raw [User.password] and read-model [User.version] from a serialized user. */
    @Suppress("unused")
    private abstract class UserEventMixin {
        @get:JsonIgnore
        abstract val password: String?

        @get:JsonIgnore
        abstract val version: Long?
    }

    /**
     * Serializes a [Review] with its POS and author flattened to ids (`posId`/`authorId`). The projector
     * resolves those ids back to the read model rows when it applies the event.
     */
    private class ReviewEventSerializer : ValueSerializer<Review>() {
        override fun serialize(
            value: Review,
            gen: JsonGenerator,
            ctxt: SerializationContext
        ) {
            gen.writeStartObject()
            gen.writeName("id")
            gen.writePOJO(value.id)
            gen.writeName("createdAt")
            gen.writePOJO(value.createdAt)
            gen.writeName("updatedAt")
            gen.writePOJO(value.updatedAt)
            gen.writeName("posId")
            gen.writePOJO(value.pos.id)
            gen.writeName("authorId")
            gen.writePOJO(value.author.id)
            gen.writeName("review")
            gen.writeString(value.review)
            gen.writeName("approvalCount")
            gen.writeNumber(value.approvalCount)
            gen.writeName("approved")
            gen.writeBoolean(value.approved)
            gen.writeEndObject()
        }
    }
}
