package nl.vdzon.softwarefactory.contract

import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode

/** Typeveilige constructors voor bridgeparameters die aan beide brugzijden hetzelfde wiretype geven. */
object BridgeParams {
    fun strings(vararg entries: Pair<String, String>): ObjectNode =
        JsonNodeFactory.instance.objectNode().apply { entries.forEach { (key, value) -> put(key, value) } }

    fun boolean(name: String, value: Boolean): ObjectNode =
        JsonNodeFactory.instance.objectNode().put(name, value)
}
