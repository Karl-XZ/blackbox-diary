package com.blackbox.diary.inference

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.security.MessageDigest

class LocalModels(val context:Context) {
    val root=File(context.noBackupFilesDir,"models").apply { mkdirs() }
    val sparkFile=File(root,"Spark-X2.5-4B_int4-bbd1.litertlm")
    val smeFile=File(root,"Spark-X2.5-4B-Q4_K_M.gguf")
    fun useSme()=smeFile.exists()&&SparkSmeBridge.available
    val status=MutableStateFlow(if(smeFile.exists())"Spark SME2 模型就绪" else if(sparkFile.exists())"Spark 模型已导入，等待运行验证" else "基础离线模式")
    private val verified=mutableSetOf<String>()
    private val manifest by lazy {org.json.JSONObject(context.assets.open("model-manifest.json").bufferedReader().use {it.readText()}).getJSONArray("models")}
    @Synchronized fun asset(name:String):File {
        val target=File(root,name)
        if(name in verified&&target.exists())return target
        val entry=(0 until manifest.length()).map {manifest.getJSONObject(it)}.first {it.getString("file")==name}
        fun hash(file:File):String {val digest=MessageDigest.getInstance("SHA-256");file.inputStream().use {input->val b=ByteArray(1024*1024);while(true){val n=input.read(b);if(n<0)break;digest.update(b,0,n)}};return digest.digest().joinToString(""){"%02x".format(it)}}
        if(target.exists()&&(target.length()!=entry.getLong("bytes")||hash(target)!=entry.getString("sha256")))target.delete()
        if(!target.exists()) {
            val part=File(root,"$name.part")
            context.assets.open("models/$name").use { input->part.outputStream().use { input.copyTo(it) } }
            check(part.renameTo(target))
            check(hash(target)==entry.getString("sha256")) {"BUNDLED_MODEL_CHECKSUM"}
        };verified+=name;return target
    }
    @Synchronized fun importSpark(uri:Uri) {
        val part=File(root,"spark-import.part");val hash=MessageDigest.getInstance("SHA-256");var size=0L
        try {
            context.contentResolver.openInputStream(uri)!!.use { input->part.outputStream().use { output->
                val b=ByteArray(1024*1024)
                while(true) { val n=input.read(b);if(n<0)break;size+=n;require(size<=SME_BYTES) { "MODEL_SIZE" };hash.update(b,0,n);output.write(b,0,n);status.value="正在校验模型 ${(size*100/SME_BYTES).coerceAtMost(99)}%" }
            } }
            val digest=hash.digest().joinToString(""){"%02x".format(it)}
            val sme=size==SME_BYTES&&digest==SME_SHA
            require(sme||size==SPARK_BYTES&&digest==SPARK_SHA) { "MODEL_CHECKSUM" }
            check(part.renameTo(if(sme)smeFile else sparkFile));status.value=if(sme)"Spark SME2 模型已导入" else "Spark 模型已导入"
        } catch(t:Throwable) { part.delete();status.value="导入未完成，基础模式仍可使用";throw t }
    }
    companion object {
        const val SPARK_BYTES=2482196400L
        const val SPARK_SHA="de80fca7a89c7ccac5301d1525b870a671f8ac0541066ef46b9c9d7e7b1aa146"
        const val SME_BYTES=2600224352L
        const val SME_SHA="adfcfa19a4ed6a5985da8bf565fe15f8e1a7e131d79bae2d19d48d1c40109428"
    }
}
