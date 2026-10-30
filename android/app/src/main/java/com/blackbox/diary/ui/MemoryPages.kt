package com.blackbox.diary.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blackbox.diary.AppGraph
import com.blackbox.diary.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock

@Composable fun LanguageForm(g:AppGraph,initial:Preference,onDone:()->Unit){
    var languages by remember {mutableStateOf(RecognitionLanguages.normalize(initial.recognitionLanguages))};var error by remember {mutableStateOf<String?>(null)}
    Text("听懂哪些语言。",style=MaterialTheme.typography.headlineLarge)
    Text("默认只识别普通话。多选时按每段语音判断语言；混说和语言判断仍可能出错。",lineHeight=25.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
        FilterChip(languages==setOf("zh"),{languages=setOf("zh");error=null},{Text("只中文")})
        FilterChip(languages==setOf("zh","en"),{languages=setOf("zh","en");error=null},{Text("中英文")})
        FilterChip(languages.size>2,{languages=RecognitionLanguages.available.keys.toSet();error=null},{Text("多语言")})
    }
    RecognitionLanguages.available.forEach {(code,name)->Row(Modifier.fillMaxWidth().clickable {if(code in languages&&languages.size==1)error="至少保留一种语言" else {languages=if(code in languages)languages-code else languages+code;error=null}},verticalAlignment=Alignment.CenterVertically){Checkbox(code in languages,null,modifier=Modifier.size(48.dp));Text(name)}}
    error?.let {Text(it,color=MaterialTheme.colorScheme.error)}
    Text("此设置用于之后处理的语音，不会改写已经保存的转写。屏幕 OCR 当前以中文与英文文字为主。",fontSize=13.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
    Button(onClick={g.work {g.store.savePreferences(g.store.preferences().copy(recognitionLanguages=languages))};onDone()},modifier=Modifier.fillMaxWidth()){Text("保存语言选择")}
}

@Composable fun AssistantPage(g:AppGraph,onDay:(String)->Unit){
    val history by g.assistant.history.collectAsState();val busy by g.assistant.busy.collectAsState();val index by g.memory.status.collectAsState()
    var question by remember {mutableStateOf("")};var clear by remember {mutableStateOf(false)};val list=rememberLazyListState()
    LaunchedEffect(Unit){g.assistant.refresh()};LaunchedEffect(history.size){if(history.isNotEmpty()){if(history.last().question==question.trim())question="";list.animateScrollToItem(history.lastIndex)}}
    Column(Modifier.fillMaxSize().imePadding()){
        Column(Modifier.padding(horizontal=24.dp).padding(top=26.dp,bottom=12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically){Text("与日记聊聊。",style=MaterialTheme.typography.headlineLarge);TextButton(onClick={clear=true},enabled=history.isNotEmpty()){Text("清空")}}
            Text("只从你已保存的日记里寻找答案。",color=MaterialTheme.colorScheme.onSurfaceVariant,fontSize=14.sp)
            Text(index,color=MaterialTheme.colorScheme.primary,fontSize=12.sp)
        }
        LazyColumn(Modifier.weight(1f),state=list,contentPadding=PaddingValues(horizontal=24.dp,vertical=12.dp),verticalArrangement=Arrangement.spacedBy(24.dp)){
            if(history.isEmpty())item {Column(verticalArrangement=Arrangement.spacedBy(16.dp)){
                Text("记得片段，\n也记得它来自哪里。",fontSize=24.sp,lineHeight=36.sp)
                Text("可以问“最近有什么让我开心的事”，或“上次提到那个计划是什么时候”。没有依据时，助手会直接说明。",lineHeight=26.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                listOf("最近我都记录了什么？","找找关于散步的日记").forEach {sample->OutlinedButton(onClick={question=sample},modifier=Modifier.fillMaxWidth()){Text(sample)}}
            }}
            items(history,key={it.createdMs}){turn->Column(verticalArrangement=Arrangement.spacedBy(14.dp)){
                Surface(shape=RoundedCornerShape(18.dp),color=MaterialTheme.colorScheme.surfaceVariant){Text(turn.question,Modifier.padding(16.dp),lineHeight=25.sp)}
                Text(turn.text,lineHeight=28.sp)
                if(turn.generated)Text("AI 梳理 · 请结合日记原文核对",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                turn.sources.forEachIndexed {i,h->OutlinedCard(onClick={if(g.memory.live(h))onDay(h.day) else g.message.value="这条日记已变化，请重新提问"}){Column(Modifier.fillMaxWidth().padding(14.dp),verticalArrangement=Arrangement.spacedBy(7.dp)){Text("[${i+1}] ${h.day} · 查看日记",fontSize=13.sp,color=MaterialTheme.colorScheme.primary);Text(h.text,fontSize=13.sp,lineHeight=22.sp,maxLines=4)}}}
            }}
            if(busy)item {Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)){CircularProgressIndicator(Modifier.size(20.dp),strokeWidth=2.dp);Text("正在查阅日记…",fontSize=14.sp);TextButton(onClick={g.assistant.cancel()}){Text("停止")}}}
        }
        Row(Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=12.dp),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.Bottom){
            OutlinedTextField(question,{question=TextRules.takeCp(it,500)},label={Text("问问你的日记")},modifier=Modifier.weight(1f),maxLines=4,shape=RoundedCornerShape(18.dp))
            FilledIconButton(onClick={val q=question.trim();if(q.isNotBlank())g.assistant.ask(q)},enabled=!busy&&question.isNotBlank(),modifier=Modifier.size(52.dp)){Icon(Icons.Outlined.Send,"发送问题")}
        }
    }
    if(clear)AlertDialog(onDismissRequest={clear=false},title={Text("清空对话？")},text={Text("本机的助手问答会清除，日记保留。")},confirmButton={TextButton(onClick={g.assistant.clear();clear=false}){Text("清空")}},dismissButton={TextButton(onClick={clear=false}){Text("取消")}})
}

@Composable fun AudioMeaningPanel(g:AppGraph,event:Event,onConfirmed:()->Unit){
    val sourceEpoch=remember(event.id){g.store.epoch}
    val changes by g.store.changes.collectAsState();val meaning=remember(event.id,changes){g.interpretations.meaning(event)}
    var text by remember(event.id,meaning){mutableStateOf(meaning?.meaning?:event.text)};var analyzing by remember {mutableStateOf(false)};val scope=rememberCoroutineScope()
    HorizontalDivider();Text("环境语音 · 原始转写",fontWeight=FontWeight.Medium)
    Text(SpeakerPolicy.label(event.metadata["speaker"]?:"UNKNOWN"),fontSize=13.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
    event.metadata["language"]?.let {Text("检测语言：${RecognitionLanguages.available[it]?:"未确定"}",fontSize=13.sp)}
    if(meaning!=null){Text(if(!meaning.meaningful)"AI 已过滤 · 暂无可恢复的有效含义" else "AI 自动修正 · ${if(meaning.uncertain)"含义仍有不确定之处" else "已完成"}",color=MaterialTheme.colorScheme.primary);if(meaning.meaningful)Text(meaning.meaning,lineHeight=26.sp);if(meaning.reason.isNotBlank())Text(meaning.reason,fontSize=13.sp,lineHeight=22.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}
    else Text("整理日记时会自动分析、过滤无意义片段并修正识别错误。原始转写始终保留。语音可能来自旁人、设备播放或噪声，不默认代表你的经历。",fontSize=13.sp,lineHeight=23.sp)
    OutlinedButton(enabled=!analyzing&&meaning==null,onClick={analyzing=true;scope.launch {try {withContext(Dispatchers.IO){g.pipeline.mutex.withLock {g.interpretations.analyze(g.store.read {events(event.day)},sourceEpoch,setOf(event.id))}}}finally {analyzing=false}}}){Text(if(analyzing)"正在分析…" else if(meaning==null)"分析这段语音" else "已保留原文与分析")}
    OutlinedTextField(text,{text=it},label={Text("可选：补充或改写")},modifier=Modifier.fillMaxWidth(),minLines=3)
    Text("AI 修正会自动用于日记，无需逐条确认。这里可额外补写你自己的说明，并关联原始语音。",fontSize=12.sp,lineHeight=21.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
    Button(enabled=text.isNotBlank(),onClick={g.work {g.interpretations.confirm(event,text,sourceEpoch);g.message.value="补充说明已保存为手记"};onConfirmed()}){Text("另存为我的补充说明")}
}

@Composable fun VoiceprintPanel(g:AppGraph,onRecord:()->Unit){
    val changes by g.store.changes.collectAsState();val registered=remember(changes){g.voice.enrolled()};val prefs=remember(changes){g.store.preferences()};val progress by g.voice.progress.collectAsState();val audio by g.capture.audio.collectAsState()
    var consent by remember {mutableStateOf(false)};var delete by remember {mutableStateOf(false)}
    DisposableEffect(Unit){onDispose {g.voice.cancel()}}
    Text("认出熟悉的声音。",style=MaterialTheme.typography.headlineLarge)
    Text("声纹是可选的辅助线索。未录制或无法匹配时保持未知；多人重叠、噪声和设备回放都可能造成误判。匹配也只标为“疑似本人”，不用于身份认证。关闭或删除后不再进行后续匹配，历史语音和日记保留。",lineHeight=26.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
    if(registered)Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween){Text("使用声纹辅助分析");Switch(prefs.voiceprintEnabled,{g.work {g.store.savePreferences(g.store.preferences().copy(voiceprintEnabled=it))}})}
    Text("在安静环境独自朗读 18 秒，至少包含 9 秒有效语音。只加密保存声纹特征，注册原音不会保存。",lineHeight=25.sp)
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Checkbox(consent,{consent=it});Text("我同意在本机录制并分析我的声纹",fontSize=14.sp)}
    if(audio.state!=CaptureState.OFF){Text("请先停止声音采集，再录制声纹。",color=MaterialTheme.colorScheme.primary);OutlinedButton(onClick={g.capture.stop(Channel.MICROPHONE)}){Text("停止声音采集")}}
    progress?.let {Text(it,color=MaterialTheme.colorScheme.primary);LinearProgressIndicator(Modifier.fillMaxWidth());TextButton(onClick={g.voice.cancel()}){Text("取消录制")}}
    Button(onClick=onRecord,enabled=consent&&progress==null&&audio.state==CaptureState.OFF,modifier=Modifier.fillMaxWidth()){Text(if(registered)"重新录制声纹" else "开始录制声纹")}
    if(registered)TextButton(onClick={delete=true}){Text("永久删除我的声纹",color=MaterialTheme.colorScheme.error)}
    if(delete)AlertDialog(onDismissRequest={delete=false},title={Text("删除声纹？")},text={Text("删除本机声纹特征，停止声音与屏幕采集，并清空助手对话。历史语音和日记保留。")},confirmButton={TextButton(onClick={delete=false;g.work {g.deletion.deleteVoiceprint()}}){Text("永久删除")}},dismissButton={TextButton(onClick={delete=false}){Text("取消")}})
}
