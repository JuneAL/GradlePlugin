package com.nier.packer.support

import com.nier.packer.support.ext.allocateBuffer
import com.nier.packer.support.ext.finish
import com.nier.packer.support.ext.slice
import com.nier.packer.support.ext.write
import java.io.IOException
import java.nio.ByteBuffer

/**
 * Created by Nier
 * Date 2018/8/20
 */


/**
 * 添加一个追加信息到SignBlock的payload中
 */
internal fun addPayload(apk: Apk, payload: IExtraPayloadData) {
    if (apk.invalid() && verifyApk(apk.source)) {
        throw IllegalArgumentException("apk not init, do you forget invoke init()")
    }
    //先将旧的数据读取出来
    val payloads = readPayload(apk)
    //检查是否存在V2的签名
    if (payloads.isEmpty() || payloads[com.nier.packer.APK_SIGN_V2_KEY] == null) {
        throw IOException("Missing v2 sign block.")
    }
    //构造新的payload数据集并且写入到apk中
    val payloadDataContent = apk.mExtraPayloadProtocol.wrap(payload)
    payloads[payload.key()] = payloadDataContent
    writeValues(apk, payloads)
}

internal fun getPayloadById(apk: Apk, id: Int): ByteBuffer? {
    return readPayload(apk)[id]
}

/**
 * 读取解析SignBlock中payload中的键值对数据
 */
private fun readPayload(apk: Apk): HashMap<Int, ByteBuffer> {
    if (apk.invalid()) {
        throw IllegalArgumentException("apk not init, do you forget invoke init()")
    }
    apk.readOnlyChannel {
        //从apk的SignBlock的数据段中读取
        println("signBlockOffset = ${apk.mSignBlockOffset}, signBlockSize = ${apk.mSignBlockSize.toInt()}")
        position(apk.mSignBlockOffset + com.nier.packer.APK_SIGN_BLOCK_SIZE_BYTE_SIZE)
        val payloadBuffer = allocateBuffer(apk.mSignBlockSize.toInt() - com.nier.packer.SIGN_BLOCK_PAYLOAD_ID_BYTE_SIZE - com.nier.packer.APK_SIGN_BLOCK_MAGIC_NUM_BYTE_SIZE * 2)
        read(payloadBuffer)
        payloadBuffer.position(0)
        return readPayloadValues(payloadBuffer)
    }
    throw IOException("unknow exception happened.")
}

/**
 * 递归获取payload中保存的所有键值对
 */
private fun readPayloadValues(signBlock: ByteBuffer, values: HashMap<Int, ByteBuffer> = HashMap()): HashMap<Int, ByteBuffer> {
    println("apk sign block remain -> ${signBlock.remaining()}")
    if (signBlock.remaining() < com.nier.packer.SIGN_BLOCK_PAYLOAD_VALUE_LENGTH_BYTE_SIZE) {
        return values
    }
    val valueSize = signBlock.long
    if (signBlock.remaining() < valueSize || valueSize < com.nier.packer.SIGN_BLOCK_PAYLOAD_ID_BYTE_SIZE) {
        throw IOException("Invalid sign block payload values. sign block size = $valueSize, sign block remain size = ${signBlock.remaining()}")
    }
    val id = signBlock.int

    val content = signBlock.slice(valueSize.toInt() - com.nier.packer.SIGN_BLOCK_PAYLOAD_ID_BYTE_SIZE)
    println("content.position() = ${content.position()}, content.limit() = ${content.limit()}")
    values[id] = content
    return readPayloadValues(signBlock, values)
}


/**
 * 根据apk的SignBlock协议写入到apk中
 */
private fun writeValues(apk: Apk, payloads: HashMap<Int, ByteBuffer>) {
    apk.channel {
        val outer = this
        println("Before write extra value, apk size = ${size()}")

        //在数据写入改变前将原始的CentralDirectory后面的数据暂存起来等待最后写入
        val sourceRemainAfterSignBlock = allocateBuffer((size() - apk.mCentralDirectoryStartOffset).toInt()) {
            position(apk.mCentralDirectoryStartOffset)
            outer.read(this)
            finish()
        }

        //跳过开始8子节用于记录SignBlock大小的字段，先进行Payload的写入
        position(apk.mSignBlockOffset + com.nier.packer.APK_SIGN_BLOCK_SIZE_BYTE_SIZE) //跳过size
        var signBlockLength: Long = 0

        //循环写入新的payload到SignBlock中
        payloads.entries.forEach { payloadEntry ->
            println("payload value limit = ${payloadEntry.value.limit()}")
            //写入Payload Entry 数据长度
            val payloadLength = payloadEntry.value.limit() + com.nier.packer.SIGN_BLOCK_PAYLOAD_ID_BYTE_SIZE

            write {
                allocateBuffer(com.nier.packer.SIGN_BLOCK_PAYLOAD_VALUE_LENGTH_BYTE_SIZE).apply {
                    putLong(payloadLength.toLong())
                    finish()
                }
            }
            //写入int型的Key值
            write {
                allocateBuffer(com.nier.packer.SIGN_BLOCK_PAYLOAD_ID_BYTE_SIZE).apply {
                    putInt(payloadEntry.key)
                    finish()
                }
            }
            //写入Payload数据体
            write { payloadEntry.value }
            //计算数据长度
            signBlockLength += com.nier.packer.SIGN_BLOCK_PAYLOAD_VALUE_LENGTH_BYTE_SIZE + payloadLength
        }
        //不包括开头的size，因此这里只加上1个size的长度8
        signBlockLength += com.nier.packer.APK_SIGN_BLOCK_SIZE_BYTE_SIZE
        signBlockLength += com.nier.packer.APK_SIGN_BLOCK_MAGIC_NUM_BYTE_SIZE * 2

        //写入末尾的SignBock长度字段
        write {
            allocateBuffer(com.nier.packer.APK_SIGN_BLOCK_SIZE_BYTE_SIZE) {
                putLong(signBlockLength)
                finish()
            }
        }

        //写入低8位魔数
        write {
            allocateBuffer(com.nier.packer.APK_SIGN_BLOCK_MAGIC_NUM_BYTE_SIZE) {
                putLong(com.nier.packer.APK_SIGN_BLOCK_MAGIC_LOW)
                finish()
            }
        }

        //写入高8位魔数
        write {
            allocateBuffer(com.nier.packer.APK_SIGN_BLOCK_MAGIC_NUM_BYTE_SIZE).apply {
                putLong(com.nier.packer.APK_SIGN_BLOCK_MAGIC_HIGH)
                finish()
            }
        }

        //记录新Central directory start offset，稍后需要更新到End of central directory中
        val newCentralDirectoryStartOffset = position()

        //写入头部的SignBock长度字段
        position(apk.mSignBlockOffset)

        write {
            allocateBuffer(com.nier.packer.APK_SIGN_BLOCK_SIZE_BYTE_SIZE).apply {
                putLong(signBlockLength)
                finish()
            }
        }

        //将之前暂存的CentralDirectory直至结尾的所有数据重新写回到apk中
        position(newCentralDirectoryStartOffset)
        write { sourceRemainAfterSignBlock }

        //更新End of central directory中记录Central directory start offset的数据段
        write {
            val newEOCDSignature = findApkEOCDSignatureOffset(this)
            //推算出eocd中cdso的位置offset
            position(calculateCentralDirectoryOffset(newEOCDSignature))

            allocateBuffer(com.nier.packer.OFFSET_OF_START_OF_CENTRAL_DIRECTORY_BYTE_SIZE) {
                putInt(newCentralDirectoryStartOffset.toInt())
                finish()
            }
        }
    }
}