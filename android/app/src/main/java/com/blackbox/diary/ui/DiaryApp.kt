@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.blackbox.diary.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blackbox.diary.AppGraph
import com.blackbox.diary.core.*
import com.blackbox.diary.processing.Schedule
import kotlinx.coroutines.*
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Locale

private val Paper=Color(0xFFF8F7F3)
private val Ink=Color(0xFF252D28)
private val Moss=Color(0xFF466451)
private val Muted=Color(0xFF657067)
private val Line=Color(0xFFE2E5DC)
private val Soft=Color(0xFFEDF0E8)
private val Error=Color(0xFF9B4541)
private val colors=lightColorScheme(primary=Moss,onPrimary=Color.White,primaryContainer=Soft,onPrimaryContainer=Ink,secondary=Moss,onSecondary=Color.White,secondaryContainer=Soft,onSecondaryContainer=Moss,tertiary=Moss,onTertiary=Color.White,tertiaryContainer=Soft,onTertiaryContainer=Ink,background=Paper,onBackground=Ink,surface=Paper,onSurface=Ink,surfaceVariant=Soft,onSurfaceVariant=Muted,outline=Muted,outlineVariant=Line,error=Error)
private data class Content(val events:List<Event> = emptyList(),val diary:Diary?=null,val days:List<String> = emptyList(),val prefs:Preference=Preference(),val favorite:Boolean=false,val audio:CoverageReport?=null,val screen:CoverageReport?=null,val candidate:Diary?=null,val pending:Int=0,val failed:Int=0,val processing:List<String> = emptyList(),val analysisPending:Int=0,val filteredAudio:Int=0,val aiIssue:String?=null,val yesterday:Diary?=null,val summaryPending:Boolean=false)

private fun readableDiary(d:Diary?)=d!=null&&(d.editor=="USER"||d.blocks.any {it.templateId?.startsWith("diary.summary.")==true})

private fun emptyDiaryMessage(c:Content):String=when {
    c.events.isEmpty()->"这一天尚无可整理的来源。开启记录，或补写一个想留下的片段。"
    c.summaryPending->"正在提炼重点、合并重复内容并撰写每日总结。原始转写和逐条纠错可在来源中查看。"
    !c.prefs.interpretAudio->"已保留 ${c.events.size} 条来源。AI 自动纠错已关闭，可在识别设置中开启。"
    c.analysisPending>0->"已保留 ${c.events.size} 条来源，还有 ${c.analysisPending} 段语音等待自动处理。\n"+when(c.aiIssue){"MEMORY_LOW"->"内存暂不足，任务已保留。";"THERMAL_PAUSED"->"手机温度较高，稍后继续。";"BUDGET_OR_STATE_LIMIT"->"本日处理额度已用完，任务已保留；接电或次日继续。";null->"AI 会自动过滤无意义语音、修正识别错误，原始 ASR 保留。";else->"上次 AI 处理未完成，可以重新整理；原始 ASR 保留。"}
    c.filteredAudio>0->"AI 已完成语音分析，暂无可写入正文的有效内容。\n已过滤 ${c.filteredAudio} 段无法恢复含义的语音，原始 ASR 保留在来源中。"
    else->"来源已保留，暂无可呈现的正文。可重新整理或补写说明。"
}

@Composable fun DiaryApp(g:AppGraph,onAudio:()->Unit,onScreen:()->Unit,onImport:()->Unit,onExport:(String,Boolean)->Unit,onBiometric:()->Unit,onVoiceRecord:()->Unit = {}) {
    MaterialTheme(colorScheme=colors,typography=Typography(bodyLarge=androidx.compose.ui.text.TextStyle(fontSize=16.sp,lineHeight=26.sp),headlineLarge=androidx.compose.ui.text.TextStyle(fontSize=32.sp,lineHeight=42.sp,fontFamily=FontFamily.Serif),titleLarge=androidx.compose.ui.text.TextStyle(fontSize=23.sp,lineHeight=32.sp))) {
        val unlocked by g.lock.unlocked.collectAsState();val snackbar=remember { SnackbarHostState() }
        val message by g.message.collectAsState()
        LaunchedEffect(message){message?.let { snackbar.showSnackbar(it);g.message.value=null }}
        Surface(Modifier.fillMaxSize(),color=Paper) {
            if(!unlocked)LockPage(g,onBiometric) else {
                var page by remember { mutableIntStateOf(0) };var day by remember { mutableStateOf(LocalDate.now().toString()) }
                var detail by remember { mutableStateOf(false) };var sheet by remember { mutableStateOf<String?>(null) };var event by remember { mutableStateOf<Event?>(null) }
                var confirmDelete by remember { mutableStateOf(false) };var eventToDelete by remember {mutableStateOf<Event?>(null)};var exportPrompt by remember { mutableStateOf(false) };var edit by remember { mutableStateOf(false) }
                val changes by g.store.changes.collectAsState();val processing by g.pipeline.busy.collectAsState()
                val contentEpoch=remember(changes){g.store.epoch}
                val viewedDay=day
                LaunchedEffect(contentEpoch){event=null;sheet=null;edit=false}
                val content by produceState(Content(),changes,day) { value=withContext(Dispatchers.IO){g.store.read { Content(events(day),diary(day),days(),preferences(),favorite(day),g.capture.coverage(Channel.MICROPHONE,day),g.capture.coverage(Channel.SCREEN,day),diary(day,"CANDIDATE"),audioPending(day),audioFailed(day),processingReport(day),g.interpretations.pending(day),scalar("SELECT count(*) FROM audio_meaning m JOIN artifact a ON a.id=m.event_id WHERE a.owner_day_id=? AND m.meaningful=0",day).toInt(),setting("ai_last_issue_SUMMARY")?:setting("ai_last_issue_INTERPRET"),diary(LocalDate.now().minusDays(1).toString()),g.composer.needs(day)) }} }
                BackHandler(detail||sheet!=null||event!=null){when {event!=null->event=null;sheet!=null->sheet=null;else->detail=false}}
                Scaffold(containerColor=Paper,snackbarHost={SnackbarHost(snackbar)},bottomBar={if(!detail)NavigationBar(containerColor=Paper,tonalElevation=0.dp,modifier=Modifier.border(BorderStroke(.5.dp,Line))) {
                    listOf("今日" to Icons.Outlined.WbSunny,"日历" to Icons.Outlined.CalendarMonth,"查找" to Icons.Outlined.Search,"助手" to Icons.Outlined.ChatBubbleOutline,"设置" to Icons.Outlined.Tune).forEachIndexed { i,(label,icon)->NavigationBarItem(selected=page==i,onClick={page=i;if(i==0)day=LocalDate.now().toString()},icon={Icon(icon,label,Modifier.size(23.dp))},label={Text(label,fontSize=12.sp)},colors=NavigationBarItemDefaults.colors(indicatorColor=Soft,selectedIconColor=Moss,selectedTextColor=Moss)) }
                }}) { padding->
                    Box(Modifier.padding(padding)) {
                        if(detail)DetailPage(day,content,processing,onBack={detail=false},onEdit={edit=true},onExport={exportPrompt=true},onDelete={confirmDelete=true},onEvent={event=it},onGenerate={g.work { Schedule.requestDay(g.app,viewedDay,contentEpoch) }},onFavorite={g.work { g.store.setFavorite(viewedDay,!content.favorite,contentEpoch) }},onVersions={sheet="versions"},onTags={sheet="tags"})
                        else when(page) {
                            0->TodayPage(g,day,content,processing,onAudio,onScreen,onNote={sheet="note"},onDetail={detail=true},onGenerate={g.work { Schedule.requestDay(g.app,viewedDay,contentEpoch) }},onEvent={event=it},onPause={sheet="pause"},onYesterday={day=LocalDate.now().minusDays(1).toString();detail=true})
                            1->CalendarPage(content.days,day,{day=it;detail=true})
                            2->SearchPage(g,onResult={hit->day=hit.day;detail=true;if(hit.source!="DIARY")g.work {val e=g.store.read {events(hit.day).firstOrNull {it.id==hit.sourceId}};withContext(Dispatchers.Main){if(e!=null)event=e else g.message.value="这条来源已删除"}}})
                            3->AssistantPage(g,onDay={day=it;detail=true})
                            else->SettingsPage(g,content.prefs,onImport,onSettings={sheet=it},onDelete={sheet="deleteRange"})
                        }
                    }
                }
                if(sheet!=null)ModalBottomSheet(onDismissRequest={sheet=null},containerColor=Paper,sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=sheet=="languages"||sheet=="voiceprint")) {
                    Column(Modifier.fillMaxWidth().padding(horizontal=24.dp).padding(bottom=32.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                        when(sheet) {
                            "note"->NoteForm(g,day){sheet=null}
                            "languages"->LanguageForm(g,content.prefs){sheet=null}
                            "voiceprint"->VoiceprintPanel(g,onVoiceRecord)
                            "pause"->{Heading("让记录休息一下","分别管理声音和屏幕")
                                Channel.entries.forEach { c->Text(if(c==Channel.MICROPHONE)"声音记录" else "屏幕记录",fontWeight=FontWeight.Medium)
                                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){listOf(15,60).forEach { m->OutlinedButton(onClick={g.work { g.capture.pause(c,m) };sheet=null}){Text("$m 分钟")}};OutlinedButton(onClick={g.work { g.capture.pause(c,null) };sheet=null}){Text("手动恢复")}}
                                    TextButton(onClick={g.work { g.capture.stop(c) };sheet=null}){Text("停止这项记录")}
                                }
                            }
                            "versions"->{Heading("日记版本","自动整理不会覆盖你的修改")
                                var selected by remember {mutableStateOf<Diary?>(null)}
                                val versions=remember(changes){g.store.read { rows("SELECT revision_no,editor,state,artifact_id FROM diary_revision WHERE day_id=? ORDER BY revision_no DESC",day) }}
                                versions.forEach { v->Row(Modifier.fillMaxWidth().clickable {selected=g.store.read {diary(day,revisionId=v[3])}}.padding(vertical=16.dp),horizontalArrangement=Arrangement.SpaceBetween){Text("第 ${v[0]} 版 · ${if(v[1]=="USER")"手动编辑" else "自动整理"}");Text(when(v[2]){"PUBLISHED"->"当前";"CANDIDATE"->"候选";else->"历史"},color=Muted)} }
                                selected?.let {version->Text(version.title,fontWeight=FontWeight.Medium);Text(version.blocks.joinToString("\n\n"){it.text},lineHeight=26.sp);if(version.state!="PUBLISHED")OutlinedButton(onClick={g.work {g.store.restore(version.day,version.id,content.diary?.revision,contentEpoch)};sheet=null}){Text("从这个版本另存为当前日记")}}
                                content.candidate?.let { candidate->Text(candidate.blocks.joinToString("\n\n"){it.text},lineHeight=26.sp);Button(onClick={g.work { g.store.publish(candidate.day,candidate.title,candidate.blocks,"USER",content.diary?.revision,contentEpoch,candidate.fallback) };sheet=null}){Text("采用这份候选版本")}}
                            }
                            "tags"->{var tags by remember { mutableStateOf(g.store.read { scalar("SELECT tags FROM day_label WHERE day=?",day).takeUnless { it=="0" }?:"" }) };Heading("给这一天加标签","使用英文逗号分隔，例如：工作,旅行")
                                OutlinedTextField(tags,{tags=it},label={Text("标签")},modifier=Modifier.fillMaxWidth());Button(onClick={g.work { g.store.label(viewedDay,content.favorite,tags,contentEpoch) };sheet=null}){Text("保存标签")}}
                            "deleteRange"->{var from by remember {mutableStateOf(day)};var to by remember {mutableStateOf(day)};var error by remember {mutableStateOf<String?>(null)}
                                Heading("删除一段记忆","相关的日记、来源、历史版本和索引会一并清理。删除后无法恢复。")
                                OutlinedTextField(from,{from=it},label={Text("开始日期 YYYY-MM-DD")});OutlinedTextField(to,{to=it},label={Text("结束日期 YYYY-MM-DD")});error?.let {Text(it,color=Error)}
                                Button(onClick={val a=runCatching {LocalDate.parse(from)}.getOrNull();val b=runCatching {LocalDate.parse(to)}.getOrNull();if(a==null||b==null||a>b)error="请输入有效日期范围" else {g.work { g.deletion.deleteDays(g.store.read {days().filter {it>=from&&it<=to}.toSet()}) };sheet=null}},colors=ButtonDefaults.buttonColors(containerColor=Error)){Text("确认删除这个日期范围")}}
                            else->PreferenceForm(g,sheet!!,content.prefs){sheet=null}
                        }
                    }
                }
                event?.let { e->ModalBottomSheet(onDismissRequest={event=null},containerColor=Paper) {
                    Column(Modifier.fillMaxWidth().padding(24.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                        Heading(sourceName(e.source),"${clock(e.startMs)} · ${if(e.source==Source.AUDIO)"ASR 原始转写" else if(e.quality==Quality.REVIEW)"原始识别 · 未校准" else "原始文字"}")
                        Text(e.text,lineHeight=28.sp);Text("内容只表示该记录中出现的文字。识别结果可能有误。",color=Muted,fontSize=13.sp)
                        if(e.source==Source.AUDIO)AudioMeaningPanel(g,e){event=null}
                        Text("记录 ${e.id.take(8)} · 版本 ${e.revision}",fontSize=12.sp,color=Muted)
                        TextButton(onClick={eventToDelete=e;event=null}){Text("删除这条来源及关联内容",color=Error)}
                        Spacer(Modifier.height(24.dp))
                    }
                } }
                eventToDelete?.let { e->AlertDialog(onDismissRequest={eventToDelete=null},title={Text("删除这条来源？")},text={Text("由它生成的段落、摘要、日记版本和索引也会删除。独立手记会保留。记录将停止，删除后无法恢复。")},confirmButton={TextButton(onClick={eventToDelete=null;g.work {g.deletion.deleteEvents(setOf(e.id))}}){Text("永久删除",color=Error)}},dismissButton={TextButton(onClick={eventToDelete=null}){Text("保留")}}) }
                if(confirmDelete)AlertDialog(onDismissRequest={confirmDelete=false},title={Text("删除 $day？")},text={Text("这一天的来源、日记、历史版本和关联索引会一并删除。记录将停止，完成后需要重新开启。")},confirmButton={TextButton(onClick={confirmDelete=false;detail=false;g.work {g.deletion.deleteDays(setOf(viewedDay))}}){Text("永久删除",color=Error)}},dismissButton={TextButton(onClick={confirmDelete=false}){Text("保留")}})
                if(exportPrompt)AlertDialog(onDismissRequest={exportPrompt=false},title={Text("导出这一天")},text={Text("导出的文件是明文，保存在你选择的位置。其他有权访问该位置的应用可能读取它。")},confirmButton={TextButton(onClick={exportPrompt=false;onExport(day,true)}){Text("导出 Markdown")}},dismissButton={Row {TextButton(onClick={exportPrompt=false;onExport(day,false)}){Text("导出 TXT")};TextButton(onClick={exportPrompt=false}){Text("取消")}}})
                if(edit&&content.diary!=null)EditDialog(g,content.diary!!,onDismiss={edit=false})
                val deletion by g.deletion.progress.collectAsState()
                deletion?.let { p->AlertDialog(onDismissRequest={if(p.complete)g.deletion.progress.value=null},title={Text(if(p.complete)"删除完成" else "正在清理")},text={Column(verticalArrangement=Arrangement.spacedBy(16.dp)){Text(p.message);if(!p.complete&&p.state!="BLOCKED")LinearProgressIndicator(Modifier.fillMaxWidth())}},confirmButton={if(p.complete||p.state=="BLOCKED")TextButton(onClick={g.deletion.progress.value=null}){Text("知道了")}}) }
            }
        }
    }
}

@Composable private fun LockPage(g:AppGraph,onBiometric:()->Unit) {
    var pin by remember {mutableStateOf("")};var repeat by remember {mutableStateOf("")};var working by remember {mutableStateOf(false)};var error by remember {mutableStateOf<String?>(null)}
    val configured=remember {g.lock.configured};val scope=rememberCoroutineScope()
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(32.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.Center) {
        Icon(Icons.Outlined.AutoStories,"黑匣子日记",Modifier.size(36.dp),tint=Moss);Spacer(Modifier.height(32.dp))
        Text(if(configured)"你好，\n回到自己的生活。" else "把日子，\n留给自己。",style=MaterialTheme.typography.headlineLarge,lineHeight=46.sp)
        Spacer(Modifier.height(16.dp));Text(if(configured)"输入 PIN，打开黑匣子日记。" else "先设置一个 6–12 位数字 PIN。\n日记只保存在这台设备，不需要账号。",color=Muted,lineHeight=25.sp)
        Spacer(Modifier.height(32.dp))
        OutlinedTextField(pin,{if(it.length<=12&&it.all {ch->ch in '0'..'9'})pin=it},label={Text(if(configured)"PIN" else "设置 PIN")},visualTransformation=PasswordVisualTransformation(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.NumberPassword),modifier=Modifier.fillMaxWidth(),singleLine=true)
        if(!configured){Spacer(Modifier.height(12.dp));OutlinedTextField(repeat,{if(it.length<=12&&it.all {ch->ch in '0'..'9'})repeat=it},label={Text("再输入一次")},visualTransformation=PasswordVisualTransformation(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.NumberPassword),modifier=Modifier.fillMaxWidth(),singleLine=true)}
        error?.let {Text(it,color=Error,modifier=Modifier.padding(top=12.dp))};Spacer(Modifier.height(24.dp))
        Button(enabled=!working&&pin.length>=6,onClick={if(!configured&&pin!=repeat){error="两次 PIN 不一致"} else {working=true;scope.launch {val ok=withContext(Dispatchers.IO){if(configured)g.lock.verify(pin) else {g.lock.configure(pin);true}};working=false;if(!ok){error=if(g.lock.remainingSeconds()>0)"请 ${g.lock.remainingSeconds()} 秒后重试" else "PIN 不正确";pin=""}}}},modifier=Modifier.fillMaxWidth().heightIn(min=52.dp),shape=RoundedCornerShape(14.dp)){if(working)CircularProgressIndicator(Modifier.size(22.dp),color=Color.White,strokeWidth=2.dp) else Text(if(configured)"打开日记" else "开始使用")}
        if(configured&&g.store.read {preferences().biometric})TextButton(onClick=onBiometric,modifier=Modifier.align(Alignment.CenterHorizontally)){Text("使用生物识别")}
        Spacer(Modifier.height(40.dp));Text("本地保存  ·  离线处理  ·  随时暂停",color=Muted,fontSize=12.sp)
    }
}

@Composable private fun TodayPage(g:AppGraph,day:String,c:Content,busy:Boolean,onAudio:()->Unit,onScreen:()->Unit,onNote:()->Unit,onDetail:()->Unit,onGenerate:()->Unit,onEvent:(Event)->Unit,onPause:()->Unit,onYesterday:()->Unit) {
    val audio by g.capture.audio.collectAsState();val screen by g.capture.screen.collectAsState()
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(24.dp),verticalArrangement=Arrangement.spacedBy(24.dp)) {
        item {Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically){Text("黑匣子日记",fontSize=13.sp,color=Muted,letterSpacing=2.sp);IconButton(onClick={g.lock.lock()}){Icon(Icons.Outlined.Lock,"锁定日记",Modifier.size(21.dp))}}}
        item {Column(verticalArrangement=Arrangement.spacedBy(8.dp)){Text(LocalDate.parse(day).format(DateTimeFormatter.ofPattern("M 月 d 日  EEEE",Locale.CHINESE)),color=Muted,fontSize=14.sp);Text("今天，也值得记住。",style=MaterialTheme.typography.headlineLarge);Text("让生活自然发生，留一点给以后。",color=Muted,fontSize=14.sp)}}
        item {Column(Modifier.fillMaxWidth().background(Soft,RoundedCornerShape(20.dp)).padding(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text("记录状态",fontWeight=FontWeight.Medium);TextButton(onClick=onPause,contentPadding=PaddingValues(0.dp),modifier=Modifier.height(24.dp)){Text("暂停管理",fontSize=13.sp)}}
            CaptureRow("声音",Icons.Outlined.MicNone,audio,c.audio,onStart={if(audio.state==CaptureState.PAUSED_USER)g.work {g.capture.resume(Channel.MICROPHONE)} else onAudio()},onStop={g.work {g.capture.stop(Channel.MICROPHONE)}})
            HorizontalDivider(color=Line)
            CaptureRow("屏幕",Icons.Outlined.CropPortrait,screen,c.screen,onStart={if(screen.state==CaptureState.PAUSED_USER)g.work {g.capture.resume(Channel.SCREEN)} else onScreen()},onStop={g.work {g.capture.stop(Channel.SCREEN)}})
            Text("屏幕只提取文字，不保存图片。\n阅读日记时会自动避开本应用。",fontSize=12.sp,color=Muted,lineHeight=19.sp)
        }}
        if(c.yesterday!=null)item {Column(Modifier.fillMaxWidth().background(Soft,RoundedCornerShape(18.dp)).clickable(onClick=onYesterday).padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text("昨天的总结",fontSize=18.sp,fontWeight=FontWeight.Medium);Icon(Icons.Outlined.ArrowOutward,"阅读昨天的总结",Modifier.size(20.dp))}
            Text(c.yesterday.day,color=Muted,fontSize=12.sp)
            Text(if(readableDiary(c.yesterday))c.yesterday.blocks.joinToString("\n\n"){it.text} else "正在把昨天的素材整理成连贯的日记总结，逐条语音解释会留在来源里。",lineHeight=27.sp,maxLines=5,overflow=TextOverflow.Ellipsis)
            Text("阅读完整总结",color=Moss,fontSize=13.sp)
        }}
        item {Column(Modifier.fillMaxWidth().border(1.dp,Line,RoundedCornerShape(18.dp)).clickable(onClick=onDetail).padding(20.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text("今天的总结",fontSize=17.sp,fontWeight=FontWeight.Medium);Icon(Icons.Outlined.ArrowOutward,"阅读日记",Modifier.size(20.dp),tint=Muted)}
            Text(if(readableDiary(c.diary))c.diary!!.blocks.joinToString("\n\n"){it.text} else emptyDiaryMessage(c),color=if(c.diary==null)Muted else Ink,lineHeight=27.sp,maxLines=5,overflow=TextOverflow.Ellipsis)
            if(c.summaryPending&&readableDiary(c.diary))Text("有新记录等待更新到总结",fontSize=12.sp,color=Muted)
            if(c.diary?.fallback==true)SmallLabel("基础模板整理")
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)){Button(onClick=onGenerate,enabled=!busy,shape=RoundedCornerShape(12.dp)){Text(if(busy)"正在总结…" else "生成每日总结")};TextButton(onClick=onNote){Text("补写一笔")}}
        }}
        item {Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text("留下的片段",fontSize=18.sp);Text("${c.events.size} 条",color=Muted,fontSize=13.sp)}}
        if(c.pending>0||c.failed>0)item {Text("${c.pending} 段声音待处理${if(c.failed>0)" · ${c.failed} 项处理失败" else ""}",color=Muted,fontSize=13.sp)}
        if(c.events.isEmpty())item {Column(Modifier.fillMaxWidth().padding(vertical=16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){Text("还没有记录",fontSize=16.sp);Text("未开启采集和没有活动，是不同的事。\n这里会如实显示已经留下的内容。",color=Muted,fontSize=14.sp,lineHeight=23.sp)}}
        items(c.events.asReversed().take(3),key={it.id}) {EventRow(it){onEvent(it)}}
        if(c.events.size>3)item {TextButton(onClick=onDetail){Text("查看当天全部记录")}}
        item {Text("只属于这台设备的，生活备忘。",Modifier.fillMaxWidth().padding(vertical=12.dp),fontSize=12.sp,color=Muted)}
    }
}
@Composable private fun CaptureRow(title:String,icon:ImageVector,status:CaptureStatus,coverage:CoverageReport?,onStart:()->Unit,onStop:()->Unit) {
    val recording=status.state in listOf(CaptureState.RECORDING,CaptureState.PAUSED_RULE)
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
        Icon(icon,title,Modifier.size(23.dp),tint=Moss)
        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)){Text("$title · ${stateName(status)}",fontSize=15.sp);Text("采集覆盖 ${coverage?.ratio?.let {"${(it*100).toInt()}%"}?:"—"}",fontSize=12.sp,color=Muted);status.reason?.takeUnless {it=="USER_STOP"}?.let {Text(it,fontSize=12.sp,color=Muted)}}
        OutlinedButton(onClick=if(recording)onStop else onStart,contentPadding=PaddingValues(horizontal=14.dp),shape=RoundedCornerShape(10.dp)){Text(if(recording)"停止" else if(status.state==CaptureState.PAUSED_USER)"恢复" else "开启",fontSize=13.sp)}
    }
}
@Composable private fun CalendarPage(days:List<String>,selected:String,onDay:(String)->Unit) {
    var month by remember {mutableStateOf(YearMonth.now())};val first=month.atDay(1);val offset=first.dayOfWeek.value-1
    LazyColumn(contentPadding=PaddingValues(24.dp),verticalArrangement=Arrangement.spacedBy(24.dp)) {
        item {Heading("日子，慢慢成册。","每一个留下过痕迹的日子，都在这里。")}
        item {Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween){IconButton(onClick={month=month.minusMonths(1)}){Icon(Icons.Outlined.ChevronLeft,"上个月")};Text("${month.year} 年 ${month.monthValue} 月",fontSize=20.sp);IconButton(onClick={month=month.plusMonths(1)}){Icon(Icons.Outlined.ChevronRight,"下个月")}}}
        item {Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Row {listOf("一","二","三","四","五","六","日").forEach {Text(it,Modifier.weight(1f).wrapContentWidth(),color=Muted,fontSize=12.sp)}}
            repeat((offset+month.lengthOfMonth()+6)/7) { week->Row {repeat(7){weekday->val n=week*7+weekday-offset+1;val date=if(n in 1..month.lengthOfMonth())month.atDay(n).toString() else null
                Column(Modifier.weight(1f).heightIn(min=48.dp).then(if(date!=null)Modifier.clickable {onDay(date)} else Modifier).padding(vertical=10.dp),horizontalAlignment=Alignment.CenterHorizontally) {Text(if(date!=null)n.toString() else "",color=if(date==LocalDate.now().toString())Moss else Ink,fontWeight=if(date==LocalDate.now().toString())FontWeight.Bold else FontWeight.Normal);Spacer(Modifier.height(5.dp));Box(Modifier.size(4.dp).background(if(date in days)Moss else Color.Transparent,RoundedCornerShape(2.dp)))}}
            } }
        }}
        item {HorizontalDivider(color=Line)}
        item {Text("最近留下的日子",fontSize=18.sp)}
        if(days.isEmpty())item {Text("第一篇日记，等你慢慢写。",color=Muted)}
        items(days) {day->Row(Modifier.fillMaxWidth().clickable {onDay(day)}.padding(vertical=12.dp),horizontalArrangement=Arrangement.SpaceBetween){Text(day,fontSize=16.sp);Icon(Icons.Outlined.ChevronRight,"打开这一天",tint=Muted)}}
    }
}
@Composable private fun SearchPage(g:AppGraph,onResult:(SearchHit)->Unit) {
    var query by remember {mutableStateOf("")};var from by remember {mutableStateOf("")};var to by remember {mutableStateOf("")};var tag by remember {mutableStateOf("")};var source by remember {mutableStateOf<String?>(null)};var favorite by remember {mutableStateOf(false)};var filters by remember {mutableStateOf(false)}
    val changes by g.store.searchChanges.collectAsState()
    val currentEpoch=remember(changes){g.store.epoch}
    LaunchedEffect(currentEpoch){query="";from="";to="";tag="";favorite=false;source=null}
    var page by remember(query,from,to,tag,source,favorite,changes){mutableIntStateOf(0)}
    val results by produceState<List<SearchHit>>(emptyList(),query,from,to,tag,source,favorite,changes,page) {if(page==0)value=emptyList();delay(200);val next=withContext(Dispatchers.IO){g.store.read {search(query,from.takeIf {it.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))},to.takeIf {it.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))},source,favorite,tag,limit=100,offset=page*100)}};value=if(page==0)next else (value+next).distinctBy {it.artifactId}}
    LazyColumn(contentPadding=PaddingValues(24.dp),verticalArrangement=Arrangement.spacedBy(20.dp)) {
        item {Heading("想找回哪一刻？","输入记得的文字，找回当时的片段。")}
        item {OutlinedTextField(query,{query=it},label={Text("搜索日记与来源")},leadingIcon={Icon(Icons.Outlined.Search,null)},singleLine=true,shape=RoundedCornerShape(14.dp),modifier=Modifier.fillMaxWidth())}
        item {Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)){FilterChip(source==null,{source=null},{Text("全部")});listOf("DIARY" to "日记","USER_NOTE" to "手记","AUDIO" to "声音","SCREEN" to "屏幕").forEach {(s,label)->FilterChip(source==s,{source=s},{Text(label)})}}}
        item {TextButton(onClick={filters=!filters}){Icon(Icons.Outlined.FilterList,null);Spacer(Modifier.width(8.dp));Text(if(filters)"收起筛选" else "日期、标签与收藏")}}
        if(filters)item {Column(verticalArrangement=Arrangement.spacedBy(12.dp)){OutlinedTextField(from,{from=it},label={Text("开始 YYYY-MM-DD")},modifier=Modifier.fillMaxWidth());OutlinedTextField(to,{to=it},label={Text("结束 YYYY-MM-DD")},modifier=Modifier.fillMaxWidth());OutlinedTextField(tag,{tag=it},label={Text("标签")},modifier=Modifier.fillMaxWidth());ToggleRow("只看收藏",favorite){favorite=it}}}
        if(query.isBlank())item {Text("不需要记住完整一句话，\n几个关键词就好。",color=Muted,lineHeight=26.sp,modifier=Modifier.padding(top=32.dp))}
        else item {Text(if(results.isEmpty())"没有找到相关片段" else "找到 ${results.size} 条片段",color=Muted,fontSize=13.sp)}
        items(results.filter {it.epoch==currentEpoch},key={it.artifactId}) {hit->Column(Modifier.fillMaxWidth().clickable {onResult(hit)}.padding(vertical=12.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){Text("${hit.day}  ·  ${when(hit.source){"DIARY"->"日记";"USER_NOTE"->"手记";"AUDIO"->"声音";"SCREEN"->"屏幕";else->"记录情况"}}",fontSize=12.sp,color=Muted);val text=buildAnnotatedString {append(hit.text);SearchTokens.highlightRanges(hit.text,query).forEach {r->addStyle(SpanStyle(background=Color(0xFFE3E9D0)),r.first,r.last+1)}};Text(text,lineHeight=26.sp,maxLines=5,overflow=TextOverflow.Ellipsis);HorizontalDivider(color=Line)}}
        if(results.isNotEmpty()&&results.size==(page+1)*100)item {OutlinedButton(onClick={page++},modifier=Modifier.fillMaxWidth()){Text("查看更多片段")}}
    }
}

@Composable private fun DetailPage(day:String,c:Content,busy:Boolean,onBack:()->Unit,onEdit:()->Unit,onExport:()->Unit,onDelete:()->Unit,onEvent:(Event)->Unit,onGenerate:()->Unit,onFavorite:()->Unit,onVersions:()->Unit,onTags:()->Unit) {
    var tab by remember {mutableIntStateOf(0)}
    LazyColumn(contentPadding=PaddingValues(24.dp),verticalArrangement=Arrangement.spacedBy(22.dp)) {
        item {Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically){IconButton(onClick=onBack){Icon(Icons.Outlined.ArrowBack,"返回")};Text(day,color=Muted);IconButton(onClick=onFavorite){Icon(if(c.favorite)Icons.Outlined.BookmarkAdded else Icons.Outlined.BookmarkBorder,if(c.favorite)"取消收藏" else "收藏",tint=Moss)}}}
        item {Text(c.diary?.title?:"这一天，留下了什么？",style=MaterialTheme.typography.headlineLarge);Spacer(Modifier.height(12.dp));Text("${c.events.size} 条来源  ·  ${c.diary?.let {"第 ${it.revision} 版"}?:"尚未整理"}",color=Muted,fontSize=13.sp)}
        item {ScrollableTabRow(selectedTabIndex=tab,containerColor=Paper,edgePadding=0.dp,divider={HorizontalDivider(color=Line)}){listOf("每日总结","时间线","来源","记录情况").forEachIndexed {i,label->Tab(tab==i,{tab=i},text={Text(label)})}}}
        when(tab) {
            0->{
                if(readableDiary(c.diary)&&c.diary!!.blocks.any {it.templateId=="diary.summary.context.v1"})item {SmallLabel("依据环境记录整理 · 经历归属未确认")}
                if(c.diary?.fallback==true)item {SmallLabel("基础模板整理 · 离线")}
                if(c.candidate!=null)item {TextButton(onClick=onVersions){Text("有一份新的候选日记，点击比较")}}
                if(!readableDiary(c.diary))item {Text(emptyDiaryMessage(c),color=Muted,lineHeight=28.sp)}
                if(c.summaryPending&&readableDiary(c.diary))item {Text("有新增记录等待汇总。手动编辑会受到保护。",fontSize=13.sp,color=Muted,lineHeight=23.sp)}
                items(if(readableDiary(c.diary))c.diary!!.blocks else emptyList(),key={it.id}) {block->Column(verticalArrangement=Arrangement.spacedBy(8.dp)){Text(block.text,fontSize=17.sp,lineHeight=31.sp);if(block.eventIds.isNotEmpty())TextButton(onClick={(c.events.firstOrNull {it.id==block.primarySourceId}?:c.events.firstOrNull {it.id in block.eventIds})?.let(onEvent)},contentPadding=PaddingValues(0.dp)){Text("查看来源 · ${block.eventIds.size}",fontSize=12.sp)}}}
                if(c.diary?.hasGaps==true)item {Text("当天存在采集缺口。这篇日记只呈现已经留下的片段。",color=Muted,lineHeight=23.sp,fontSize=13.sp)}
                item {Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){Button(onClick=onGenerate,enabled=!busy){Text(if(busy)"正在总结…" else "重新生成总结")};if(c.diary!=null)OutlinedButton(onClick=onEdit){Text("编辑日记")}}}
                if(c.diary!=null)item {Row(Modifier.horizontalScroll(rememberScrollState())){TextButton(onClick=onExport){Text("导出")};TextButton(onClick=onVersions){Text("历史版本")};TextButton(onClick=onTags){Text("标签")}}}
            }
            1,2->{if(c.events.isEmpty())item {Text("这一天没有留下记录。",color=Muted)};items(c.events,key={it.id}) {EventRow(it){onEvent(it)}}}
            else->{item {Heading("如实呈现记录情况","采集覆盖、处理完成和识别准确性分开计算。")}
                listOf("声音采集" to c.audio,"屏幕采集" to c.screen).forEach {(title,r)->item {Column(Modifier.fillMaxWidth().background(Soft,RoundedCornerShape(16.dp)).padding(18.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){Text(title,fontWeight=FontWeight.Medium);Text(r?.ratio?.let {"${(it*100).toInt()}%"}?:"—",fontSize=30.sp,fontFamily=FontFamily.Serif);if((r?.unknownMs?:0)>0)Text("系统时间发生变化，本日覆盖率无法可靠确认。",color=Muted,fontSize=13.sp) else Text("应采 ${duration(r?.expectedMs?:0)} · 已采 ${duration(r?.capturedMs?:0)}\n主动排除 ${duration(r?.excludedMs?:0)} · 缺口 ${duration(r?.gapMs?:0)}",fontSize=13.sp,lineHeight=23.sp,color=Muted)}}}
                items(c.processing){Text(it,color=Muted,lineHeight=26.sp)}
                item {Text("待处理声音：${c.pending} 段\n最终处理失败：${c.failed} 项\n识别准确性：尚未完成本设备校准",color=Muted,lineHeight=26.sp)}
                item {Text("没有识别出文字可能是静音、未变化或识别失败，不能据此认定没有活动。",lineHeight=25.sp,fontSize=14.sp)}
            }
        }
        item {Spacer(Modifier.height(12.dp));TextButton(onClick=onDelete){Icon(Icons.Outlined.DeleteOutline,null,tint=Error);Spacer(Modifier.width(8.dp));Text("删除这一天",color=Error)}}
    }
}

@Composable private fun SettingsPage(g:AppGraph,p:Preference,onImport:()->Unit,onSettings:(String)->Unit,onDelete:()->Unit) {
    val models by g.models.status.collectAsState()
    LazyColumn(contentPadding=PaddingValues(24.dp),verticalArrangement=Arrangement.spacedBy(22.dp)) {
        item {Heading("按自己的节奏。","记录的边界，由你决定。")}
        item {GroupTitle("记录与整理")}
        item {SettingsRow(Icons.Outlined.Bedtime,"睡眠时段",if(p.sleepEnabled)"${p.sleepStart} — ${p.sleepEnd}" else "已关闭"){onSettings("sleep")}}
        item {SettingsRow(Icons.Outlined.AutoStories,"日记偏好","${p.generationTime} 尝试整理 · ${p.length}"){onSettings("diary")}}
        item {SettingsRow(Icons.Outlined.VerifiedUser,"识别内容","未校准内容${if(p.includeReview)"带标记进入正文" else "仅保留在来源"}"){onSettings("recognition")}}
        item {SettingsRow(Icons.Outlined.Translate,"语音识别语言",RecognitionLanguages.label(p.recognitionLanguages)){onSettings("languages")}}
        item {SettingsRow(Icons.Outlined.RecordVoiceOver,"我的声纹",if(g.voice.enrolled())if(p.voiceprintEnabled)"已开启 · 仅作辅助判断" else "已录制 · 当前关闭" else "可选，尚未录制"){onSettings("voiceprint")}}
        item {HorizontalDivider(color=Line);Spacer(Modifier.height(22.dp));GroupTitle("隐私与设备")}
        item {SettingsRow(Icons.Outlined.Lock,"应用锁",if(p.biometric)"PIN 与生物识别" else "PIN 解锁"){onSettings("lock")}}
        item {SettingsRow(Icons.Outlined.Memory,"离线模型",models,onImport)}
        item {SettingsRow(Icons.Outlined.PhonelinkLock,"离线与设备检查","查看权限、存储与模型状态"){onSettings("device")}}
        item {SettingsRow(Icons.Outlined.FolderDelete,"保留与删除",p.retentionDays?.let {"保留 $it 天"}?:"手动管理"){onSettings("retention")}}
        item {SettingsRow(Icons.Outlined.DeleteOutline,"删除日期范围","永久清理相关内容",onDelete)}
        item {HorizontalDivider(color=Line);Spacer(Modifier.height(16.dp));Text("黑匣子日记  1.1.0 内测版",fontSize=13.sp,color=Muted);Spacer(Modifier.height(8.dp));Text("每一次记录都留在本机。\n没有账号，也没有云端副本。",fontSize=13.sp,color=Muted,lineHeight=23.sp)}
    }
}
@Composable private fun PreferenceForm(g:AppGraph,section:String,initial:Preference,onDone:()->Unit) {
    var p by remember {mutableStateOf(initial)};var error by remember {mutableStateOf<String?>(null)}
    when(section) {
        "sleep"->{Heading("睡眠时段","这个时段主动排除采集，不计入覆盖率分母。")
            ToggleRow("启用睡眠时段",p.sleepEnabled){p=p.copy(sleepEnabled=it)}
            OutlinedTextField(p.sleepStart,{p=p.copy(sleepStart=it)},label={Text("开始 HH:mm")},modifier=Modifier.fillMaxWidth());OutlinedTextField(p.sleepEnd,{p=p.copy(sleepEnd=it)},label={Text("结束 HH:mm")},modifier=Modifier.fillMaxWidth())}
        "diary"->{Heading("日记偏好","系统会在约定时间尝试整理，实际执行受系统调度影响。")
            OutlinedTextField(p.generationTime,{p=p.copy(generationTime=it)},label={Text("整理时间 HH:mm")},modifier=Modifier.fillMaxWidth())
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){listOf("简短","标准","详细").forEach {value->FilterChip(p.length==value,{p=p.copy(length=value)},{Text(value)})}}
            Text("正文使用经过来源校验的观察模板。",fontSize=13.sp,color=Muted)}
        "recognition"->{Heading("自动理解与纠错","AI 自动判断语音是否有意义，结合上下文修正识别错误；原始 ASR 单独保留。")
            ToggleRow("自动过滤杂音并修正语音",p.interpretAudio){p=p.copy(interpretAudio=it)}
            ToggleRow("将 AI 修正结果自动写入日记",p.includeAudioInterpretations){p=p.copy(includeAudioInterpretations=it)}
            Text("无需逐条确认。语音可能来自旁人、电视或杂音，不默认是你说的。能够恢复含义时自动记录，存在歧义时标注不确定；无法恢复含义的片段保留原文但不进入正文。",color=Muted,lineHeight=25.sp)
            ToggleRow("额外纳入未修正的原始识别",p.includeReview){p=p.copy(includeReview=it)}
            Text("开启后，相关句子会明确标为「低可信识别」。手记始终按你主动提供的内容处理。",color=Muted,lineHeight=25.sp)
            Text("手机号、证件号、银行卡号和显式凭据会在入库前遮蔽。",fontSize=13.sp,color=Muted)}
        "lock"->{Heading("应用锁","应用锁控制阅读，已授权的后台录音可独立运行。")
            ToggleRow("使用生物识别",p.biometric){p=p.copy(biometric=it)}
            Text("离开应用后自动锁定",fontSize=14.sp)
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){listOf(0,30,60).forEach {seconds->FilterChip(p.lockTimeoutSeconds==seconds,{p=p.copy(lockTimeoutSeconds=seconds)},{Text(if(seconds==0)"立即" else "$seconds 秒")})}}
            TextButton(onClick={g.lock.lock();onDone()}){Text("立即锁定")}}
        "retention"->{Heading("保留期限","到期时会执行与手动删除相同的完整清理。强行停止应用时，清理会在下次运行继续。")
            listOf<Int?>(null,30,90,365).forEach {days->Row(Modifier.fillMaxWidth().clickable {p=p.copy(retentionDays=days)},verticalAlignment=Alignment.CenterVertically){RadioButton(p.retentionDays==days,{p=p.copy(retentionDays=days)});Text(days?.let {"保留 $it 天"}?:"一直保留，手动删除")}}
            Text("原始声音缓存始终最多保留 24 小时，不受这里的设置影响。",color=Muted,lineHeight=25.sp)}
        "device"->{Heading("离线与设备检查","以下是这台设备的实际状态。")
            val info=remember {g.app.packageManager.getPackageInfo(g.app.packageName,android.content.pm.PackageManager.GET_PERMISSIONS.toLong().let { android.content.pm.PackageManager.PackageInfoFlags.of(it) })}
            val network=info.requestedPermissions?.any {it=="android.permission.INTERNET"}==true
            val memory=remember {android.app.ActivityManager.MemoryInfo().also {g.app.getSystemService(android.app.ActivityManager::class.java).getMemoryInfo(it)}}
            Text("联网权限：${if(network)"异常，存在联网权限" else "未声明"}\nAndroid：${android.os.Build.VERSION.RELEASE}\n设备：${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}\n内存：${memory.totalMem/(1024*1024*1024)} GB\n可用存储：${g.app.noBackupFilesDir.usableSpace/(1024*1024*1024)} GB\n语音模型：本地内置\n屏幕 OCR：本地内置\nSpark：${when {g.models.useSme()->"Spark-X2.5-4B · SME2 本地就绪";g.models.sparkFile.exists()->"Spark-X2.5-4B · 已导入";else->"尚未导入，使用基础模板"}}",lineHeight=29.sp)
            Text("Spark 离线文件约 2.48 GB，可从设置的「离线模型」选择本地文件导入。导入时会校验完整 SHA-256。",fontSize=13.sp,color=Muted,lineHeight=23.sp)
            Text("长时间后台稳定性、耗电与识别准确性需要分别进行真机测试，模型就绪不代表这些指标已经完成验证。",color=Muted,lineHeight=25.sp)}
    }
    error?.let {Text(it,color=Error)}
    if(section!="device")Button(onClick={val valid=runCatching {LocalTime.parse(p.sleepStart);LocalTime.parse(p.sleepEnd);LocalTime.parse(p.generationTime)}.isSuccess;if(!valid)error="时间格式应为 HH:mm，例如 23:00" else {g.work {g.store.savePreferences(p);Schedule.install(g.app,p.generationTime)};onDone()}},modifier=Modifier.fillMaxWidth()){Text("保存设置")}
}

@Composable private fun NoteForm(g:AppGraph,day:String,onDone:()->Unit) {
    var text by remember {mutableStateOf("")};var saving by remember {mutableStateOf(false)};val scope=rememberCoroutineScope()
    val epoch=remember {g.store.epoch};var error by remember {mutableStateOf<String?>(null)}
    Heading("补写一笔","你写下的，也是这一天的一部分。")
    OutlinedTextField(text,{if(it.length<=6000)text=it},label={Text("此刻想留下的话")},modifier=Modifier.fillMaxWidth().heightIn(min=180.dp),minLines=5)
    error?.let {Text(it,color=Error)}
    Button(enabled=text.isNotBlank()&&!saving,onClick={saving=true;scope.launch {try {val accepted=withContext(Dispatchers.IO){val now=System.currentTimeMillis();val clean=TextRules.sanitize(text.trim());g.store.commitEvent(Event(g.store.id(),day,Source.USER_NOTE,clean,now,now,ZoneId.systemDefault().id,Quality.HIGH),epoch)};if(accepted)onDone() else {error="正在进行隐私清理，未保存这次修改";saving=false}}catch(t:Exception){if(t is CancellationException)throw t;error="保存未完成，请稍后重试";saving=false}}},modifier=Modifier.fillMaxWidth()){Text(if(saving)"正在保存…" else "保存手记")}
}
@Composable private fun EditDialog(g:AppGraph,diary:Diary,onDismiss:()->Unit) {
    var title by remember {mutableStateOf(diary.title)};var blocks by remember {mutableStateOf(diary.blocks)};var extra by remember {mutableStateOf("")};var saving by remember {mutableStateOf(false)}
    val scope=rememberCoroutineScope();val epoch=remember {g.store.epoch}
    androidx.compose.ui.window.Dialog(onDismissRequest=onDismiss,properties=androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth=false)) {
        Surface(Modifier.fillMaxSize(),color=Paper){Column(Modifier.safeDrawingPadding().padding(24.dp)) {
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){TextButton(onClick=onDismiss){Text("取消")};TextButton(enabled=!saving,onClick={saving=true;scope.launch {try {withContext(Dispatchers.IO){g.store.transaction {val all=blocks.filter {it.text.isNotBlank()}.map {it.copy(origin=if(it.origin=="USER_AUTHORED")"USER_AUTHORED" else "USER_EDITED_AI")}.toMutableList();if(extra.isNotBlank()){val now=System.currentTimeMillis();val text=TextRules.sanitize(extra);val id=id();check(commitEvent(Event(id,diary.day,Source.USER_NOTE,text,now,now,ZoneId.systemDefault().id,Quality.HIGH),epoch));all+=DiaryBlock(id(),text,"USER_AUTHORED",listOf(id),userNoteRootId=id)};check(publish(diary.day,title,all,"USER",diary.revision,epoch,diary.fallback)!=null)}};onDismiss()}catch(_:Exception){g.message.value="保存失败，内容可能已变化，请返回后重试";saving=false}}}){Text(if(saving)"保存中…" else "保存")}}
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(20.dp)){Text("把日记改成你的语气。",style=MaterialTheme.typography.titleLarge);OutlinedTextField(title,{title=it},label={Text("标题")},modifier=Modifier.fillMaxWidth());blocks.forEachIndexed {index,b->OutlinedTextField(b.text,{value->blocks=blocks.toMutableList().also {it[index]=b.copy(text=value)}},label={Text("段落 ${index+1}")},modifier=Modifier.fillMaxWidth(),minLines=3)};OutlinedTextField(extra,{extra=it},label={Text("新增一段自己的话")},modifier=Modifier.fillMaxWidth(),minLines=3);Text("修改会另存为新版本，来源关联会保留。",color=Muted,fontSize=13.sp)}
        }}
    }
}
@Composable private fun Heading(title:String,subtitle:String) {Column(verticalArrangement=Arrangement.spacedBy(12.dp)){Text(title,style=MaterialTheme.typography.headlineLarge);Text(subtitle,color=Muted,fontSize=14.sp,lineHeight=24.sp)}}
@Composable private fun GroupTitle(title:String){Text(title,color=Muted,fontSize=12.sp,letterSpacing=1.sp)}
@Composable private fun SettingsRow(icon:ImageVector,title:String,subtitle:String,onClick:()->Unit){Row(Modifier.fillMaxWidth().clickable(onClick=onClick).padding(vertical=6.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(16.dp)){Icon(icon,title,Modifier.size(23.dp),tint=Moss);Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(6.dp)){Text(title,fontSize=16.sp);Text(subtitle,color=Muted,fontSize=12.sp,lineHeight=19.sp)};Icon(Icons.Outlined.ChevronRight,null,Modifier.size(20.dp),tint=Muted)}}
@Composable private fun ToggleRow(title:String,value:Boolean,onChange:(Boolean)->Unit){Row(Modifier.fillMaxWidth().heightIn(min=52.dp),verticalAlignment=Alignment.CenterVertically){Text(title,Modifier.weight(1f),fontSize=15.sp);Switch(value,onChange)}}
@Composable private fun SmallLabel(text:String){Text(text,Modifier.background(Soft,RoundedCornerShape(5.dp)).padding(horizontal=8.dp,vertical=5.dp),color=Moss,fontSize=11.sp)}
@Composable private fun EventRow(e:Event,onClick:()->Unit){Row(Modifier.fillMaxWidth().clickable(onClick=onClick).padding(vertical=8.dp),horizontalArrangement=Arrangement.spacedBy(16.dp)){Text(clock(e.startMs),fontSize=12.sp,color=Muted,modifier=Modifier.padding(top=3.dp));Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(8.dp)){Text(sourceName(e.source),fontSize=12.sp,color=Moss);Text(e.text,maxLines=3,overflow=TextOverflow.Ellipsis,lineHeight=25.sp);if(e.quality==Quality.REVIEW)Text(if(e.source==Source.AUDIO)"原始 ASR · 保留追溯" else "原始识别 · 未校准",fontSize=11.sp,color=Muted);HorizontalDivider(color=Line,modifier=Modifier.padding(top=12.dp))}}}
private fun clock(ms:Long)=Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm"))
private fun duration(ms:Long)="${ms/3_600_000}时${ms%3_600_000/60_000}分"
private fun sourceName(s:Source)=when(s){Source.AUDIO->"声音片段";Source.SCREEN->"屏幕文字";Source.USER_NOTE->"我的手记";Source.SYSTEM->"记录情况"}
private fun stateName(s:CaptureStatus)=when(s.state){CaptureState.OFF->"未开启";CaptureState.RECORDING->"记录中";CaptureState.REQUESTING_PERMISSION->"等待授权";CaptureState.PAUSED_USER->"已暂停";CaptureState.PAUSED_RULE->"睡眠暂停";CaptureState.NEEDS_PERMISSION->"需要授权";CaptureState.INTERRUPTED->"已中断";CaptureState.PAUSED_MAINTENANCE->"维护暂停"}
