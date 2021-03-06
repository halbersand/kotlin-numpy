/*
 * Copyright 2019 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.numkt.core

import org.jetbrains.numkt.Interpreter
import org.jetbrains.numkt.NumKtException
import org.jetbrains.numkt.callFunc
import org.jetbrains.numkt.logic.arrayEqual
import java.nio.ByteBuffer
import java.nio.ByteOrder

@Experimental(level = Experimental.Level.WARNING)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
annotation class ExperimentalNumkt

/**
 * Wrapper over `numpy.ndarray`. Stores a pointer to ndarray and [DirectBuffer][java.nio.ByteBuffer]
 * above the memory allocated by numpy for the array.
 *
 * @property base Base object. Currently a stub.
 * @property data [ByteBuffer] of array's data.
 * @property dtype Type of array's elements.
 * @property itemsize Length of one array element in bytes.
 * @property ndim Number of array dimensions.
 * @property scalar If the array is a scalar, contains it, otherwise null.
 * @property shape [IntArray] of array dimensions.
 * @property size Number of elements in the array.
 * @property strides [IntArray] of bytes to step in each dimension when traversing an array.
 * @property t The transposed array
 */
class KtNDArray<T : Any> private constructor(
    private val pointer: Long,
    dataBuffer: ByteBuffer?,
    scalar: T?,
    private val p: Long
) {

    private val interp: Interpreter = Interpreter.interpreter!!

    val data: ByteBuffer? = dataBuffer?.order(ByteOrder.nativeOrder())

    // IntArray of array dimensions.
    val shape: IntArray
        get() = interp.getField("shape", getPointer(), IntArray::class.java)

    // Number of array dimensions.
    val ndim: Int
        get() = interp.getField("ndim", getPointer(), Int::class.javaObjectType)

    // Length of one array element in bytes.
    val itemsize: Int by lazy {
        interp.getField("itemsize", getPointer(), Int::class.javaObjectType)
    }

    // Number of elements in the array.
    val size: Int by lazy {
        interp.getField("size", getPointer(), Int::class.javaObjectType)
    }

    // strides - array int of bytes to step in each dimension when traversing an array.
    // may changed
    val strides: IntArray
        get() = interp.getField("strides", getPointer(), IntArray::class.java)

    // Data-type in numpy of the array’s elements.
    val dtype: Class<T>
        get() = interp.getField("dtype", getPointer(), Class::class.java)

    // the transposed array
    val t: KtNDArray<T> by lazy {
        this.transpose()
    }

    // base object, if memory is from some other object (e.g view)
    var base: KtNDArray<*>? = null
        private set

    var scalar: T? = scalar
        private set

    private fun getPointer(): Long = if (isNotScalar()) pointer else throw NumKtException("KtNDArray is scalar.")

    fun isScalar(): Boolean = !isNotScalar()

    fun isNotScalar(): Boolean = scalar == null


    operator fun get(vararg index: Int): KtNDArray<T> =
        interp.getValue(getPointer(), index.map { it.toLong() }.toLongArray())

    operator fun get(vararg index: Long): KtNDArray<T> = interp.getValue(getPointer(), index)

    operator fun get(vararg slices: Slice): KtNDArray<T> = interp.getValue(getPointer(), slices)

    operator fun get(intRange: IntRange): KtNDArray<T> = this[intRange.toSlice()]

    operator fun get(vararg indexes: Any): KtNDArray<T> {
        return if (indexes.size == 1) {
            when (val ind = indexes[0]) {
                is IntArray -> get(*ind)
                is LongArray -> get(*ind)
                else -> interp.getValue(
                    getPointer(),
                    indexes.map { if (it is IntRange) it.toSlice() else it }.toTypedArray()
                )
            }
        } else {
            interp.getValue(getPointer(), indexes.map { if (it is IntRange) it.toSlice() else it }.toTypedArray())
        }
    }

    @JvmName("setVarArg")
    operator fun set(vararg index: Int, element: T) {
        interp.setValue(getPointer(), index.map { it.toLong() }.toLongArray(), element)
    }

    @JvmName("setArray")
    operator fun set(indexes: IntArray, element: T) {
        interp.setValue(getPointer(), indexes.map { it.toLong() }.toLongArray(), element)
    }

    operator fun set(vararg slices: Slice, element: T) {
        interp.setValue(getPointer(), slices, element)
    }

    operator fun set(vararg indexes: Int, element: KtNDArray<T>) {
        interp.setValue(getPointer(), indexes.map { it.toLong() }.toLongArray(), element)
    }

    operator fun set(vararg indexes: Any, element: KtNDArray<T>) {
        interp.setValue(getPointer(), indexes.map { if (it is IntRange) it.toSlice() else it }.toTypedArray(), element)
    }

    /**
     * Returns [FlatIterator].
     */
    fun flatIter(): Iterator<T> {
        return FlatIterator(
            this.data ?: throw NumKtException("KtNDArray is scalar."),
            this.ndim,
            this.strides,
            this.itemsize,
            this.shape,
            this.dtype,
            p
        )
    }

    /**
     * Returns 1-D [List].
     */
    fun toList(): List<T> = ArrayList<T>(this.size).also {
        for (el in this.flatIter()) {
            it.add(el)
        }
    }

    /**
     * Returns 2-D [List].
     */
    fun toList2d(): List<List<T>> {
        assert(this.ndim == 2)
        return MutableList(shape[0]) {
            this[it].toList()
        }
    }

    /**
     * Returns 3-D [List].
     */
    fun toList3d(): List<List<List<T>>> {
        assert(this.ndim == 3)
        return MutableList(this.shape[0]) {
            this[it].toList2d()
        }
    }

    /**
     * Iterator over ndarray elements.
     *
     * Iteration takes place on the direct buffer indexes obtained from the nditer.
     * This iterator is equivalent to ndarray.flat or nditer with order 'C'.
     */
    operator fun iterator(): Iterator<KtNDArray<T>> = NDIterator(this.getPointer())

    /**
     * Uses [arrayEqual]
     */
    override fun equals(other: Any?): Boolean {
        if (other !is KtNDArray<*>)
            return false
        if (isScalar()) {
            return this.scalar == other.scalar
        }
        return arrayEqual(this, other)
    }

    override fun hashCode(): Int =
        if (isScalar())
            scalar.hashCode()
        else
            interp.getField("hashCode", pointer, Int::class.javaObjectType)

    override fun toString(): String =
        if (isScalar())
            scalar.toString()
        else
            interp.getField("toString", pointer, String::class.java)

    /**
     * If the array is not a scalar, the counter of the array decreases by one.
     * If the counter is zero, python will free up memory.
     */
    protected fun finalize() {
        if (isNotScalar())
            interp.freeArray(pointer, data!!)
    }
}

/**
 * <
 */
infix fun <T : Any, C : Number> KtNDArray<T>.lt(other: C): KtNDArray<Boolean> =
    callFunc(nameMethod = arrayOf("ndarray", "__lt__"), args = arrayOf(this, other))

infix fun <T : Any> KtNDArray<T>.lt(other: KtNDArray<T>): KtNDArray<Boolean> =
    callFunc(nameMethod = arrayOf("ndarray", "__lt__"), args = arrayOf(this, other))

/**
 * <=
 */
infix fun <T : Any, C : Number> KtNDArray<T>.le(other: C): KtNDArray<T> =
    callFunc(nameMethod = arrayOf("ndarray", "__le__"), args = arrayOf(this, other))

infix fun <T : Any> KtNDArray<T>.le(other: KtNDArray<T>): KtNDArray<T> =
    callFunc(nameMethod = arrayOf("ndarray", "__le__"), args = arrayOf(this, other))

/**
 * >
 */
infix fun <T : Any, C : Number> KtNDArray<T>.gt(other: C): KtNDArray<Boolean> =
    callFunc(nameMethod = arrayOf("ndarray", "__gt__"), args = arrayOf(this, other))

infix fun <T : Any> KtNDArray<T>.gt(other: KtNDArray<T>): KtNDArray<Boolean> =
    callFunc(nameMethod = arrayOf("ndarray", "__gt__"), args = arrayOf(this, other))

/**
 * >=
 */
infix fun <T : Any, C : Number> KtNDArray<T>.ge(other: C): KtNDArray<Boolean> =
    callFunc(nameMethod = arrayOf("ndarray", "__ge__"), args = arrayOf(this, other))

infix fun <T : Any> KtNDArray<T>.ge(other: KtNDArray<T>): KtNDArray<Boolean> =
    callFunc(nameMethod = arrayOf("ndarray", "__ge__"), args = arrayOf(this, other))

/**
 * ==
 */
infix fun <T : Any, C : Number> KtNDArray<T>.eq(other: C): KtNDArray<Boolean> =
    callFunc(nameMethod = arrayOf("ndarray", "__eq__"), args = arrayOf(this, other))

infix fun <T : Any> KtNDArray<T>.eq(other: KtNDArray<T>): KtNDArray<Boolean> =
    callFunc(nameMethod = arrayOf("ndarray", "__eq__"), args = arrayOf(this, other))

/**
 * !=
 */
infix fun <T : Any, C : Number> KtNDArray<T>.ne(other: C): KtNDArray<Boolean> =
    callFunc(nameMethod = arrayOf("ndarray", "__ne__"), args = arrayOf(this, other))

infix fun <T : Any> KtNDArray<T>.ne(other: KtNDArray<T>): KtNDArray<Boolean> =
    callFunc(nameMethod = arrayOf("ndarray", "__ne__"), args = arrayOf(this, other))
