package com.jarvis.ai.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.ai.missions.Mission
import com.jarvis.ai.missions.MissionEngine
import com.jarvis.ai.missions.MissionStep
import com.jarvis.ai.missions.MissionTrigger
import com.jarvis.ai.missions.MissionTriggerStore
import com.jarvis.ai.missions.MissionTriggerType
import com.jarvis.ai.missions.MissionVerb
import com.jarvis.ai.missions.MissionVerbs
import com.jarvis.ai.onboarding.PermissionCatalog

/**
 * Visual mission builder.
 *
 * Built strictly on top of the existing MissionEngine: it calls all(), save(),
 * delete() and run() and nothing else. Verb execution logic is untouched, so a
 * mission built here behaves exactly like one created by a text command.
 *
 * Screen-control verbs need Accessibility, and that access silently no-ops when
 * off, so the editor warns inline rather than letting a mission fail quietly.
 */

private val Ink = Color(0xFF0B0B0D)
private val Panel = Color(0xFF17171A)
private val PanelSoft = Color(0xFF1F1F23)
private val Stroke = Color(0xFF2C2C31)
private val Accent = Color(0xFFFF1744)
private val Ember = Color(0xFFFF6B00)
private val TextHi = Color(0xFFF3F3F4)
private val TextLo = Color(0xFF9A9AA2)
private val WarnAmber = Color(0xFFFFA000)

@Composable
fun MissionsScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val engine = remember { MissionEngine(context) }

    var missions by remember { mutableStateOf(engine.all()) }
    var editing by remember { mutableStateOf<Mission?>(null) }
    var creating by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<String?>(null) }

    fun reload() {
        missions = engine.all()
    }

    if (creating || editing != null) {
        MissionEditor(
            original = editing,
            onCancel = {
                creating = false
                editing = null
            },
            onSave = { mission, trigger ->
                // Renaming means the old entry has to go, otherwise both survive.
                editing?.takeIf { !it.name.equals(mission.name, ignoreCase = true) }?.let {
                    engine.delete(it.name)
                    MissionTriggerStore.clear(context, it.name)
                }
                toast = engine.save(mission)
                MissionTriggerStore.set(context, mission.name, trigger)
                reload()
                creating = false
                editing = null
            }
        )
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Ink)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 18.dp, vertical = 18.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Missions", color = TextHi, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text(
                    "Saved routines. Tap Run, or say \"run " +
                        (missions.firstOrNull()?.name ?: "good morning") + "\".",
                    color = TextLo,
                    fontSize = 12.5.sp
                )
            }
            Text(
                "Close",
                color = TextLo,
                fontSize = 14.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(onClick = onClose)
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            )
        }

        toast?.let {
            Spacer(Modifier.height(10.dp))
            Surface(color = PanelSoft, shape = RoundedCornerShape(10.dp)) {
                Text(
                    it,
                    color = TextHi,
                    fontSize = 12.5.sp,
                    modifier = Modifier
                        .clickable { toast = null }
                        .padding(10.dp)
                )
            }
        }

        Spacer(Modifier.height(14.dp))

        if (missions.isEmpty()) {
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Text("No missions yet, sir.", color = TextLo, fontSize = 14.sp)
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(missions, key = { it.name }) { mission ->
                    MissionRow(
                        mission = mission,
                        triggerLabel = MissionTriggerStore.get(context, mission.name).label,
                        onRun = { toast = engine.run(mission.name) },
                        onEdit = { editing = mission },
                        onDelete = {
                            toast = engine.delete(mission.name)
                            MissionTriggerStore.clear(context, mission.name)
                            reload()
                        }
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        GradientButton("New mission") { creating = true }
    }
}

@Composable
private fun MissionRow(
    mission: Mission,
    triggerLabel: String,
    onRun: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Surface(color = Panel, shape = RoundedCornerShape(14.dp)) {
        Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    mission.name,
                    color = TextHi,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                Text(triggerLabel, color = Ember, fontSize = 11.5.sp)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                mission.steps.size.toString() + " steps: " +
                    mission.steps.take(4).joinToString(", ") { MissionVerbs.describe(it) } +
                    if (mission.steps.size > 4) "..." else "",
                color = TextLo,
                fontSize = 12.5.sp,
                maxLines = 3
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SmallAction("Run", Accent, onRun)
                SmallAction("Edit", TextHi, onEdit)
                SmallAction("Delete", TextLo, onDelete)
            }
        }
    }
}

@Composable
private fun MissionEditor(
    original: Mission?,
    onCancel: () -> Unit,
    onSave: (Mission, MissionTrigger) -> Unit
) {
    val context = LocalContext.current
    var name by remember { mutableStateOf(original?.name ?: "") }
    val steps = remember { (original?.steps ?: emptyList()).toMutableStateList() }
    var trigger by remember {
        mutableStateOf(
            original?.let { MissionTriggerStore.get(context, it.name) } ?: MissionTrigger()
        )
    }
    var showPicker by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val accessibilityOn = remember { PermissionCatalog.isAccessibilityOn(context) }
    val needsAccessibility = steps.any {
        MissionVerbs.byAction(it.action)?.group == "Screen control"
    }

    if (showPicker) {
        VerbPickerDialog(
            onDismiss = { showPicker = false },
            onPick = { verb, argument ->
                steps.add(MissionStep(verb.action, argument))
                showPicker = false
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Ink)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 18.dp, vertical = 18.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (original == null) "New mission" else "Edit mission",
                color = TextHi,
                fontSize = 21.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            Text(
                "Cancel",
                color = TextLo,
                fontSize = 14.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(onClick = onCancel)
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            )
        }

        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            singleLine = true,
            placeholder = { Text("Mission name, e.g. good morning", color = TextLo, fontSize = 14.sp) },
            colors = missionFieldColors(),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(12.dp))
        TriggerPicker(trigger) { trigger = it }

        if (needsAccessibility && !accessibilityOn) {
            Spacer(Modifier.height(10.dp))
            Surface(color = Color(0xFF2A1F11), shape = RoundedCornerShape(10.dp)) {
                Text(
                    "This mission taps or types on screen, which needs Accessibility. " +
                        "It is OFF right now, so those steps will do nothing. " +
                        "Turn it on from menu > Permissions & access.",
                    color = WarnAmber,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(10.dp)
                )
            }
        }

        Spacer(Modifier.height(14.dp))
        Text(
            "Steps run top to bottom, with a short pause between each.",
            color = TextLo,
            fontSize = 12.sp
        )
        Spacer(Modifier.height(8.dp))

        if (steps.isEmpty()) {
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Text("No steps yet. Add the first one.", color = TextLo, fontSize = 13.sp)
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                itemsIndexed(steps) { index, step ->
                    StepRow(
                        position = index + 1,
                        label = MissionVerbs.describe(step),
                        canMoveUp = index > 0,
                        canMoveDown = index < steps.lastIndex,
                        onUp = {
                            val item = steps.removeAt(index)
                            steps.add(index - 1, item)
                        },
                        onDown = {
                            val item = steps.removeAt(index)
                            steps.add(index + 1, item)
                        },
                        onRemove = { steps.removeAt(index) }
                    )
                }
            }
        }

        error?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = Accent, fontSize = 12.5.sp)
        }

        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(modifier = Modifier.weight(1f)) {
                OutlineButton("Add step") { showPicker = true }
            }
            Box(modifier = Modifier.weight(1f)) {
                GradientButton("Save") {
                    when {
                        name.isBlank() -> error = "Give the mission a name, sir."
                        steps.isEmpty() -> error = "Add at least one step, sir."
                        else -> onSave(
                            Mission(name.trim(), steps.toList()),
                            trigger
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TriggerPicker(trigger: MissionTrigger, onChange: (MissionTrigger) -> Unit) {
    Column {
        Text("Trigger", color = TextLo, fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("Manual", trigger.type == MissionTriggerType.MANUAL) {
                onChange(trigger.copy(type = MissionTriggerType.MANUAL))
            }
            Chip("Daily", trigger.type == MissionTriggerType.DAILY) {
                onChange(trigger.copy(type = MissionTriggerType.DAILY))
            }
        }
        if (trigger.type == MissionTriggerType.DAILY) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TimeField(trigger.hour, 23) { onChange(trigger.copy(hour = it)) }
                Text(" : ", color = TextHi, fontSize = 16.sp)
                TimeField(trigger.minute, 59) { onChange(trigger.copy(minute = it)) }
                Spacer(Modifier.width(10.dp))
                Text("24-hour time", color = TextLo, fontSize = 11.5.sp)
            }
        }
    }
}

@Composable
private fun TimeField(value: Int, max: Int, onChange: (Int) -> Unit) {
    var text by remember { mutableStateOf(value.toString().padStart(2, '0')) }
    OutlinedTextField(
        value = text,
        onValueChange = { raw ->
            val digits = raw.filter { it.isDigit() }.take(2)
            text = digits
            digits.toIntOrNull()?.let { if (it in 0..max) onChange(it) }
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        colors = missionFieldColors(),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.width(74.dp)
    )
}

@Composable
private fun StepRow(
    position: Int,
    label: String,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onUp: () -> Unit,
    onDown: () -> Unit,
    onRemove: () -> Unit
) {
    Surface(color = PanelSoft, shape = RoundedCornerShape(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                position.toString(),
                color = Ember,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.width(20.dp)
            )
            Text(label, color = TextHi, fontSize = 13.5.sp, modifier = Modifier.weight(1f))
            if (canMoveUp) SmallAction("Up", TextLo, onUp)
            if (canMoveDown) SmallAction("Down", TextLo, onDown)
            SmallAction("X", Accent, onRemove)
        }
    }
}

@Composable
private fun VerbPickerDialog(
    onDismiss: () -> Unit,
    onPick: (MissionVerb, String) -> Unit
) {
    var selected by remember { mutableStateOf<MissionVerb?>(null) }
    var argument by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Panel,
        title = {
            Text(
                selected?.label ?: "Add a step",
                color = TextHi,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            val verb = selected
            if (verb == null) {
                LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                    MissionVerbs.groups.forEach { group ->
                        item(key = "group_" + group) {
                            Text(
                                group,
                                color = TextLo,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(top = 10.dp, bottom = 4.dp)
                            )
                        }
                        items(
                            MissionVerbs.all.filter { it.group == group },
                            key = { it.action }
                        ) { item ->
                            Text(
                                item.label,
                                color = TextHi,
                                fontSize = 14.5.sp,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable {
                                        if (item.needsArgument) {
                                            selected = item
                                        } else {
                                            onPick(item, "")
                                        }
                                    }
                                    .padding(vertical = 9.dp, horizontal = 6.dp)
                            )
                        }
                    }
                }
            } else {
                Column {
                    Text(
                        verb.argumentHint.orEmpty(),
                        color = TextLo,
                        fontSize = 12.5.sp
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = argument,
                        onValueChange = { argument = it },
                        singleLine = true,
                        colors = missionFieldColors(),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (verb.action == "whatsapp" || verb.action == "sms") {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Use contact | message, e.g. nazib | on my way",
                            color = TextLo,
                            fontSize = 11.5.sp
                        )
                    }
                }
            }
        },
        confirmButton = {
            val verb = selected
            if (verb != null) {
                TextButton(onClick = { onPick(verb, argument.trim()) }) {
                    Text("Add", color = Accent)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = { if (selected != null) selected = null else onDismiss() }) {
                Text(if (selected != null) "Back" else "Close", color = TextLo)
            }
        }
    )
}

@Composable
private fun Chip(label: String, active: Boolean, onClick: () -> Unit) {
    Text(
        label,
        color = if (active) Color.White else TextLo,
        fontSize = 13.sp,
        fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(if (active) Accent else PanelSoft)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    )
}

@Composable
private fun SmallAction(label: String, color: Color, onClick: () -> Unit) {
    Text(
        label,
        color = color,
        fontSize = 12.5.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(PanelSoft)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp)
    )
}

@Composable
private fun GradientButton(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Brush.linearGradient(listOf(Accent, Ember)))
            .clickable(onClick = onClick)
            .padding(vertical = 13.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun OutlineButton(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(PanelSoft)
            .clickable(onClick = onClick)
            .padding(vertical = 13.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = TextHi, fontSize = 15.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun missionFieldColors() = TextFieldDefaults.colors(
    focusedContainerColor = PanelSoft,
    unfocusedContainerColor = PanelSoft,
    focusedIndicatorColor = Accent,
    unfocusedIndicatorColor = Stroke,
    focusedTextColor = TextHi,
    unfocusedTextColor = TextHi,
    cursorColor = Accent
)
