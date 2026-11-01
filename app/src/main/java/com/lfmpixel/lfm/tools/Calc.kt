package com.lfmpixel.lfm.tools

import com.lfmpixel.lfm.llm.ToolSpec
import com.lfmpixel.lfm.llm.ToolSpec.Param
import com.lfmpixel.lfm.settings.ToolPolicy
import java.math.BigDecimal
import java.math.MathContext
import java.util.Locale
import kotlin.math.pow

/**
 * Quick math and unit conversion on the phone, exact where a small model guesses: "15% of 80", "(12+8)*3",
 * "5 km in miles", "180 c to f". Spoken words ("times", "divided by", "plus") work too.
 */
object Calc {
    /** Each unit as a factor of its group's base unit (m, kg, l, s, m/s); temperatures are special. */
    private data class Unit(val group: String, val factor: Double)

    private val units: Map<String, Unit> = buildMap {
        fun add(group: String, factor: Double, vararg names: String) = names.forEach { put(it, Unit(group, factor)) }
        add("length", 0.001, "mm", "millimeter", "millimeters", "millimetre", "millimetres")
        add("length", 0.01, "cm", "centimeter", "centimeters", "centimetre", "centimetres")
        add("length", 1.0, "m", "meter", "meters", "metre", "metres")
        add("length", 1000.0, "km", "kilometer", "kilometers", "kilometre", "kilometres")
        add("length", 0.0254, "in", "inch", "inches")
        add("length", 0.3048, "ft", "foot", "feet")
        add("length", 0.9144, "yd", "yard", "yards")
        add("length", 1609.344, "mi", "mile", "miles")
        add("mass", 0.001, "g", "gram", "grams")
        add("mass", 1.0, "kg", "kilo", "kilos", "kilogram", "kilograms")
        add("mass", 0.45359237, "lb", "lbs", "pound", "pounds")
        add("mass", 0.028349523125, "oz", "ounce", "ounces")
        add("mass", 6.35029318, "st", "stone", "stones")
        add("volume", 0.001, "ml", "milliliter", "milliliters", "millilitre", "millilitres")
        add("volume", 1.0, "l", "liter", "liters", "litre", "litres")
        add("volume", 0.24, "cup", "cups")
        add("volume", 0.0147868, "tbsp", "tablespoon", "tablespoons")
        add("volume", 0.00492892, "tsp", "teaspoon", "teaspoons")
        add("volume", 0.0295735, "floz", "fl oz", "fluid ounce", "fluid ounces")
        add("volume", 0.473176, "pint", "pints")
        add("volume", 3.78541, "gal", "gallon", "gallons")
        add("time", 1.0, "s", "sec", "secs", "second", "seconds")
        add("time", 60.0, "min", "mins", "minute", "minutes")
        add("time", 3600.0, "h", "hr", "hrs", "hour", "hours")
        add("time", 86400.0, "day", "days")
        add("time", 604800.0, "week", "weeks")
        add("speed", 1 / 3.6, "kmh", "km/h", "kph", "kilometers per hour", "kilometres per hour")
        add("speed", 0.44704, "mph", "miles per hour")
        add("speed", 1.0, "m/s", "meters per second", "metres per second")
        add("temp", 0.0, "c", "°c", "celsius", "degrees celsius", "degrees c", "centigrade")
        add("temp", 0.0, "f", "°f", "fahrenheit", "degrees fahrenheit", "degrees f")
        add("temp", 0.0, "k", "kelvin", "kelvins")
    }

    private val convert = Regex("""^(?:convert\s+)?(-?[\d.,]+)\s*(.+?)\s+(?:in|to|into|as)\s+(.+?)\??$""")

    /** The answer as words for the model: "5 km = 3.107 miles", "15% of 80 = 12". */
    fun answer(question: String): String {
        val q = question.trim().lowercase(Locale.ROOT).removePrefix("what is ").removePrefix("what's ").removePrefix("how many ").trim()
        convert.find(q)?.let { m ->
            val n = m.groupValues[1].replace(",", "").toDoubleOrNull()
            val from = units[m.groupValues[2].trim()]
            val to = units[m.groupValues[3].trim().removeSuffix(" are there").removeSuffix(" is that")]
            if (n != null && from != null && to != null) {
                if (from.group != to.group) throw ToolArgsException("Can't turn ${m.groupValues[2]} into ${m.groupValues[3]}")
                val v = if (from.group == "temp") temp(n, m.groupValues[2].trim(), m.groupValues[3].trim()) else n * from.factor / to.factor
                return "${fmt(n)} ${m.groupValues[2].trim()} = ${fmt(v)} ${m.groupValues[3].trim()}"
            }
        }
        val v = eval(q)
        return "${question.trim().removeSuffix("?").removeSuffix("=").trim()} = ${fmt(v)}"
    }

    private fun tempKind(s: String) = when {
        s.startsWith("k") -> 'k'; s.contains("f") -> 'f'; else -> 'c'
    }

    private fun temp(n: Double, from: String, to: String): Double {
        val c = when (tempKind(from)) { 'f' -> (n - 32) * 5 / 9; 'k' -> n - 273.15; else -> n }
        return when (tempKind(to)) { 'f' -> c * 9 / 5 + 32; 'k' -> c + 273.15; else -> c }
    }

    /** Up to 4 decimals (6 below 1), no trailing zeros, no exponent: 3.1069, 12, 0.0005. */
    fun fmt(v: Double): String {
        if (v.isNaN() || v.isInfinite()) throw ToolArgsException("That has no answer")
        val r = BigDecimal(v).round(MathContext(10)).setScale(if (kotlin.math.abs(v) >= 1) 4 else 6, java.math.RoundingMode.HALF_UP)
        return r.stripTrailingZeros().toPlainString().let { if (it == "-0") "0" else it }
    }

    /** + − × ÷ ^, brackets, "%" (a hundredth; "a% of b" is a share of b), and the words for them. */
    fun eval(text: String): Double {
        val s = text.lowercase(Locale.ROOT)
            .replace(Regex("""(\d),(\d{3})"""), "$1$2")
            .replace("multiplied by", "*").replace("times", "*").replace("divided by", "/").replace("over", "/")
            .replace("plus", "+").replace("minus", "-").replace("to the power of", "^").replace("squared", "^2").replace("cubed", "^3")
            .replace("percent", "%")
            .replace('×', '*').replace('x', '*').replace('÷', '/').replace('−', '-').replace("**", "^")
            .replace(Regex("""sqrt|square root of"""), "√")
            .trim().removeSuffix("?").removeSuffix("=").trim()
        if (s.isEmpty()) throw ToolArgsException("Nothing to work out")
        return Parser(s).run { val v = sum(); end(); v }
    }

    private class Parser(val s: String) {
        var i = 0
        fun skip() { while (i < s.length && s[i] == ' ') i++ }
        fun peek(): Char? { skip(); return s.getOrNull(i) }
        fun end() { if (peek() != null) throw ToolArgsException("I couldn't read \"$s\"") }
        fun sum(): Double {
            var v = product()
            while (true) when (peek()) {
                '+' -> { i++; v += product() }
                '-' -> { i++; v -= product() }
                else -> return v
            }
        }
        fun product(): Double {
            var v = power()
            while (true) {
                when {
                    peek() == '*' -> { i++; v *= power() }
                    peek() == '/' -> { i++; val d = power(); if (d == 0.0) throw ToolArgsException("Can't divide by zero"); v /= d }
                    s.startsWith("of", i) -> { i += 2; v *= power() }
                    else -> return v
                }
            }
        }
        fun power(): Double {
            val b = unary()
            return if (peek() == '^') { i++; b.pow(power()) } else b
        }
        fun unary(): Double = when (peek()) {
            '-' -> { i++; -unary() }
            '+' -> { i++; unary() }
            '√' -> { i++; kotlin.math.sqrt(unary()) }
            else -> percent()
        }
        fun percent(): Double {
            var v = atom()
            while (peek() == '%') { i++; v /= 100 }
            return v
        }
        fun atom(): Double {
            if (peek() == '(') {
                i++; val v = sum()
                if (peek() != ')') throw ToolArgsException("A bracket is missing")
                i++; return v
            }
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
            return s.substring(start, i).toDoubleOrNull() ?: throw ToolArgsException("I couldn't read \"$s\"")
        }
    }
}

class CalculateTool : Tool {
    override val spec = ToolSpec("calculate", "Work out math or convert units exactly. Use it for any sum, percent or conversion.",
        listOf(Param("question", "string", "Like 15% of 80, (12+8)*3, 5 km to miles, 180 c to f")))
    override val label = "Math and units"
    override val defaultPolicy = ToolPolicy.Do
    override val readOnly = true
    override suspend fun preview(args: Map<String, String>) = Preview("Work it out?", "sliders", "OK", listOf(Field("question", "Question", args.need("question"))))
    override suspend fun run(args: Map<String, String>): ToolResult {
        val a = Calc.answer(args.need("question"))
        return ToolResult(true, a, "Worked out", a)
    }
}
