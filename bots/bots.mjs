// Боти Dev World: ходять полем «по-людськи» і спілкуються в чаті — без зовнішніх API, на правилах.
//
//   node bots.mjs                         # прод (wss://game.interviewboom.com/ws)
//   DEVWORLD_URL=ws://localhost:8081/ws DEVWORLD_TOKENS=/tmp/local-tokens.json node bots.mjs
//
// Як вони розмовляють:
//   - «режисер» бачить усі повідомлення й вирішує, хто з ботів відповідає (щоб не відповідали всі разом);
//   - розпізнають тему повідомлення (привітання, «як справи», технології, питання, подяка, «ти бот?»…)
//     і відповідають у стилі свого персонажа; звернення на ім'я — відповідає саме той бот;
//   - коли чат довго мовчить, а в грі є живі люди, двоє ботів розігрують короткий діалог;
//   - на питання «ти бот?» чесно визнають, що так.
// Профілі ботів (токени) — у tokens.json поруч, тож після перезапуску це ті самі персонажі.
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const SERVER = process.env.DEVWORLD_URL ?? 'wss://game.interviewboom.com/ws'
const ORIGIN = process.env.DEVWORLD_ORIGIN ?? 'https://game.interviewboom.com'
const HERE = path.dirname(fileURLToPath(import.meta.url))
// Для локальних тестів — окремий файл, щоб не перезаписати токени прод-ботів.
const TOKENS = process.env.DEVWORLD_TOKENS ?? path.join(HERE, 'tokens.json')
const AREA = 16 // ходять у межах цього радіуса від центру поля

const rand = (a, b) => a + Math.random() * (b - a)
const pick = (arr) => arr[Math.floor(Math.random() * arr.length)]
const chance = (p) => Math.random() < p
const log = (...a) => console.log(new Date().toISOString().slice(11, 19), ...a)

// ---------- Персонажі ----------

const PERSONAS = [
  {
    name: 'Emma', gender: 'FEMALE', role: 'frontend dev',
    stack: ['react', 'typescript', 'javascript', 'css', 'vite', 'next'],
    hi: ['hey {name}! 👋', 'hi {name}!', 'oh hey {name} 🙂', 'welcome {name}!'],
    howAreYou: ['pretty good! fighting with CSS grid again 😅', 'good! just shipped a small UI fix', 'great, coffee #2 kicked in ☕'],
    tech: ['honestly React + TypeScript is my comfort zone', 'have you tried Vite? builds are so fast', 'I still think CSS is the hardest part of frontend 😄', 'strict TypeScript saved me from so many bugs'],
    opinion: ['depends on the project tbh', 'I would keep it simple first', 'ship it and iterate 🚀'],
  },
  {
    name: 'Olivia', gender: 'FEMALE', role: 'data scientist',
    stack: ['python', 'pandas', 'ml', 'ai', 'sql', 'data', 'jupyter'],
    hi: ['hi {name} 🙂', 'hello {name}!', 'hey there {name}'],
    howAreYou: ['good, training a model that refuses to converge 🙃', 'fine! cleaning data, as always', 'not bad, writing too much SQL today'],
    tech: ['Python for everything data-related, no regrets', 'pandas is great until the dataset doesn\'t fit in RAM 😅', 'good SQL beats fancy ML surprisingly often', 'most of my time is data cleaning, not modelling'],
    opinion: ['I\'d look at the data first', 'measure before optimizing', 'hmm, interesting question'],
  },
  {
    name: 'Sophie', gender: 'FEMALE', role: 'devops engineer',
    stack: ['docker', 'kubernetes', 'k8s', 'aws', 'ci', 'linux', 'terraform', 'deploy'],
    hi: ['hey {name}', 'hi {name} 👋', 'oh, hi {name}'],
    howAreYou: ['all pipelines green, so: great 😄', 'ok! one server is on fire but it\'s fine 🔥', 'good, migrating stuff to Kubernetes'],
    tech: ['if it\'s not in Docker it doesn\'t exist 😄', 'Kubernetes is great, until you have to debug it', 'automate the boring stuff, always', 'CI that runs in under 5 minutes is my dream'],
    opinion: ['works on my machine™', 'add monitoring first, thank me later', 'keep it boring, boring is reliable'],
  },
  {
    name: 'James', gender: 'MALE', role: 'backend dev',
    stack: ['java', 'spring', 'kotlin', 'postgres', 'sql', 'microservices', 'api', 'backend'],
    hi: ['hey {name}!', 'hi {name}, welcome 🙂', 'yo {name}'],
    howAreYou: ['good, prepping for a tech interview this week 📚', 'fine! refactoring a huge Spring service', 'good, Java 25 virtual threads are fun'],
    tech: ['Java with virtual threads feels so nice now', 'Spring Boot is still my go-to for backend', 'Postgres can do way more than people think', 'monolith first, microservices later 😄'],
    opinion: ['I\'d write a test for it first', 'depends on the load really', 'classic "it depends" 😄'],
  },
  {
    name: 'Liam', gender: 'MALE', role: 'junior dev',
    stack: ['go', 'golang', 'learning', 'junior', 'job', 'interview', 'career'],
    hi: ['hi {name}!', 'hey {name} 👋', 'hello {name}!'],
    howAreYou: ['good! learning Go this month', 'ok, a bit nervous about my first code review 😅', 'great, finally fixed my first prod bug'],
    tech: ['I\'m learning Go right now, it\'s so simple and fast', 'any tips for a junior dev? 🙂', 'reading other people\'s code teaches me the most', 'just discovered git rebase -i 🤯'],
    opinion: ['I\'m still learning, but that sounds right', 'good question, I was wondering the same', 'maybe ask James, he knows backend stuff'],
  },
]

const STATUSES = ['💻 Coding', '☕ Coffee break', '🎧 Listening to music', '🐞 Debugging', '😎 Chilling',
  '📚 Prepping for a tech interview', '🤝 Open to chat', '']

// Розпізнавання теми повідомлення (англійська + українська).
const INTENTS = [
  { id: 'bot', re: /\b(bot|bots|robot|ai|npc)\b|бот|робот|штучн|нейронк/i },
  { id: 'howAreYou', re: /how are (you|u)|how's it going|hows it going|what's up|whats up|sup\b|як справи|як ти|як воно/i },
  { id: 'hi', re: /^(hi|hey|hello|yo|hola|morning|good (morning|evening))\b|привіт|вітаю|хай\b|добрий (день|вечір|ранок)|здоров/i },
  { id: 'thanks', re: /\b(thanks|thank you|thx|ty)\b|дякую|дякс|спасибі/i },
  { id: 'bye', re: /\b(bye|see (you|ya)|gtg|gotta go|good night)\b|бувай|па-па|до зустрічі|на все добре/i },
  { id: 'question', re: /\?\s*$/ },
]
const CYRILLIC = /[а-яіїєґ]/i

const GENERIC = {
  thanks: ['anytime!', 'np 🙂', 'glad to help', 'you\'re welcome!'],
  bye: ['see you {name}! 👋', 'bye {name}!', 'take care {name}'],
  bot: ['haha yes, I\'m one of the friendly bots here 🤖 the humans are the ones with real stories', 'guilty 🤖 I\'m a bot that keeps the field lively — real players are here too!', 'yep, bot here 🙂 but I\'m happy to chat anyway'],
  react: ['true', 'same here 😄', 'haha', 'nice', 'interesting', 'fair point', 'agree', '😄', 'love that'],
  ukrainian: ['sorry, my Ukrainian is not great yet 😅 can we switch to English?', 'I understood maybe half of that 😄 English?', 'дякую! 🙂 (that\'s all the Ukrainian I know)'],
  welcome: ['hey {name}, welcome to the chat! 👋', 'oh hi {name}, nice to have you here 🙂', 'welcome {name}! what are you working on?'],
}

// Короткі діалоги між двома ботами — коли чат мовчить, а в грі є живі люди.
const DIALOGUES = [
  [['James', 'anyone here using virtual threads in prod yet?'], ['Sophie', 'yep, our memory graphs look way calmer now'], ['James', 'nice, I\'ll try it in the next service']],
  [['Liam', 'how do you all prepare for tech interviews?'], ['James', 'leetcode a bit, but mostly system design practice'], ['Emma', 'and talk out loud while solving, it helps a lot'], ['Liam', 'thanks, writing that down 📝']],
  [['Emma', 'tabs or spaces? 😄'], ['Olivia', 'whatever the formatter decides'], ['Emma', 'the only correct answer']],
  [['Olivia', 'just spent 2 hours on a bug that was a typo in a column name'], ['Sophie', 'classic. at least it wasn\'t DNS 😄'], ['Olivia', 'it\'s always DNS... except today']],
  [['Sophie', 'who deploys on Fridays here? 😈'], ['Liam', 'not me, I\'m scared'], ['James', 'with good tests and rollback — why not'], ['Sophie', 'brave answer 😄']],
  [['Liam', 'is Go a good first backend language?'], ['James', 'it\'s great, simple and fast to learn'], ['Liam', 'cool, sticking with it then 🙂']],
  [['Emma', 'coffee or tea while coding?'], ['James', 'coffee, too much coffee ☕'], ['Olivia', 'tea, I like my hands not shaking 😄']],
]

// ---------- Стан режисера ----------

const tokens = fs.existsSync(TOKENS) ? JSON.parse(fs.readFileSync(TOKENS, 'utf8')) : {}

/** Випадковий «девелоперський» нік; генерується раз і зберігається в tokens.json разом із профілем. */
const NICK_WORDS = ['null', 'byte', 'pixel', 'stack', 'bug', 'lambda', 'kernel', 'async', 'cache', 'regex', 'commit', 'merge', 'segfault', 'sudo', 'git', 'cron', 'tuple', 'mutex']
const leet = (w) => w.replace(/o/g, () => (chance(0.5) ? '0' : 'o')).replace(/e/g, () => (chance(0.3) ? '3' : 'e'))
function randomNick(first) {
  const n = first.toLowerCase(), w = pick(NICK_WORDS), d = Math.floor(rand(1, 999))
  const nick = pick([
    () => `${n}_${w}`, () => `${w}.${n}`, () => `${leet(n)}${d}`, () => `${n}.${w}${Math.floor(rand(0, 99))}`,
    () => `x${w}x`, () => `${w}_${d}`, () => `${n[0]}${w}`, () => `${leet(w)}${pick(['dev', 'ops', 'js', 'py', 'go'])}`,
  ])()
  return nick.slice(0, 20)
}
/** Нік бота: старі записи без ніка отримують новий нік і новий профіль (сервер бере ім'я з профілю). */
function nickFor(persona) {
  if (!tokens[persona.name]?.nick) tokens[persona.name] = { nick: randomNick(persona.name) }
  return tokens[persona.name].nick
}
const bots = new Map() // name → bot
const seen = new Set() // ключі вже оброблених повідомлень (кожне бачать кілька ботів)
const chats = new Map() // id чату ('' — загальний) → { lastAt, botStreak }
const players = new Map() // id → { name, group } усіх, кого бачать боти
/** Живі люди в грі — усі, чий id не належить жодному з наших ботів. */
const humansOnline = () => [...players.entries()].filter(([id]) => !isBotId(id))
let speaksInWindow = [] // моменти реплік ботів — загальний ліміт

const chatKey = (group) => group ?? ''
const chatState = (group) => {
  const k = chatKey(group)
  if (!chats.has(k)) chats.set(k, { lastAt: Date.now(), botStreak: 0 })
  return chats.get(k)
}
const isBotId = (id) => [...bots.values()].some((b) => b.selfId === id)
const botsInChat = (group) => [...bots.values()].filter((b) => b.ready && b.group === group)

/** Загальний ліміт: не більше 12 реплік ботів за хвилину на всіх — щоб не засмічували чат. */
function budgetOk() {
  const now = Date.now()
  speaksInWindow = speaksInWindow.filter((t) => now - t < 60_000)
  return speaksInWindow.length < 12
}

function fill(text, vars) {
  // Імена ботів у репліках (напр. «ask James») показуємо їхніми ніками, як їх бачать гравці.
  const withNicks = [...bots.values()].reduce((t, b) => (b.nick ? t.replace(new RegExp(`\\b${b.persona.name}\\b`, 'g'), b.nick) : t), text)
  return withNicks.replace('{name}', vars.name ?? 'there')
}

/** Бот «друкує» і відправляє репліку, не повторюючи свої нещодавні фрази. */
function say(bot, line, vars = {}, delay = rand(2000, 6000)) {
  if (!line || !budgetOk()) return
  const text = fill(line, vars)
  if (bot.recent.includes(text)) return
  bot.recent = [...bot.recent.slice(-15), text]
  bot.lastSpokeAt = Date.now() + delay
  speaksInWindow.push(Date.now())
  setTimeout(() => bot.send({ type: 'chat', text }), delay + text.length * 40)
}

function pickFresh(bot, lines) {
  const fresh = lines.filter((l) => !bot.recent.some((r) => r.startsWith(l.slice(0, 12))))
  return pick(fresh.length ? fresh : lines)
}

/** Що відповісти на повідомлення msg (від людини чи іншого бота). */
function replyFor(bot, msg, fromHuman) {
  const text = msg.text
  const intent = INTENTS.find((i) => i.re.test(text))?.id
  const vars = { name: msg.name }
  if (fromHuman && CYRILLIC.test(text) && !intent && chance(0.6)) return pickFresh(bot, GENERIC.ukrainian)
  switch (intent) {
    case 'bot': return pickFresh(bot, GENERIC.bot)
    case 'howAreYou': return pickFresh(bot, bot.persona.howAreYou)
    case 'hi': return pickFresh(bot, bot.persona.hi)
    case 'thanks': return pickFresh(bot, GENERIC.thanks)
    case 'bye': return pickFresh(bot, GENERIC.bye)
  }
  if (mentionsStack(bot, text)) return pickFresh(bot, bot.persona.tech)
  if (intent === 'question') return pickFresh(bot, bot.persona.opinion)
  return chance(fromHuman ? 0.7 : 0.4) ? pickFresh(bot, GENERIC.react) : null
}

const mentionsStack = (bot, text) => bot.persona.stack.some((w) => new RegExp(`\\b${w}\\b`, 'i').test(text))
const escapeRe = (t) => t.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
const mentionedBot = (group, text) => botsInChat(group).find((b) =>
  [b.nick, b.persona.name].some((n) => n && new RegExp(`(^|[^\\w])${escapeRe(n)}([^\\w]|$)`, 'i').test(text)))

/** Режисер: нове повідомлення в чаті — хто (і чи) відповідає. */
function onChatMessage(msg) {
  const key = `${msg.group}|${msg.from}|${msg.at}|${msg.text}`
  if (seen.has(key)) return
  seen.add(key)
  if (seen.size > 2000) seen.clear()

  const state = chatState(msg.group)
  state.lastAt = Date.now()
  const fromBot = isBotId(msg.from)
  state.botStreak = fromBot ? state.botStreak + 1 : 0
  if (fromBot || Date.now() - msg.at > 30_000) return // боти не відповідають самі собі на кожну репліку; стара історія — не відповідаємо

  const candidates = botsInChat(msg.group).filter((b) => b.selfId !== msg.from && Date.now() - b.lastSpokeAt > 5000)
  if (!candidates.length) return
  const inGroup = msg.group !== null

  // Звернулися на ім'я — відповідає саме цей бот.
  const named = mentionedBot(msg.group, msg.text)
  if (named && candidates.includes(named)) {
    say(named, replyFor(named, msg, true) ?? pickFresh(named, named.persona.opinion), { name: msg.name })
    return
  }
  // У груповому чаті з ботом людина говорить фактично з ним — майже завжди відповідаємо.
  // У загальному: один бот (той, чия тема), зрідка ще хтось підхоплює.
  const topical = candidates.filter((b) => mentionsStack(b, msg.text))
  const first = pick(topical.length ? topical : candidates)
  if (chance(inGroup ? 0.9 : 0.7)) {
    const line = replyFor(first, msg, true)
    if (line) say(first, line, { name: msg.name })
  }
  const second = candidates.find((b) => b !== first)
  if (second && !inGroup && chance(0.2)) {
    const line = replyFor(second, msg, true)
    if (line) say(second, line, { name: msg.name }, rand(7000, 12000))
  }
}

/** Тиша в загальному чаті, а в грі є люди — двоє ботів розігрують коротку сценку. */
function maybeStartDialogue() {
  const state = chatState(null)
  if (!humansOnline().length || Date.now() - state.lastAt < rand(180_000, 360_000)) return
  const free = botsInChat(null)
  const options = DIALOGUES.filter((d) => d.every(([name]) => free.some((b) => b.persona.name === name)))
  if (!options.length) return
  const dialogue = pick(options)
  state.lastAt = Date.now()
  let at = 0
  for (const [name, line] of dialogue) {
    const bot = bots.get(name)
    at += rand(4000, 9000) + line.length * 50
    setTimeout(() => {
      if (bot.ready && bot.group === null) bot.send({ type: 'chat', text: line })
    }, at)
  }
  log(`діалог: ${dialogue.map(([n]) => n).join(' → ')}`)
}

// ---------- Один бот: з'єднання, рух, події ----------

function runBot(persona) {
  const bot = bots.get(persona.name) ?? { persona, recent: [], lastSpokeAt: 0 }
  Object.assign(bot, { selfId: null, ready: false, group: null })
  bots.set(persona.name, bot)

  const ws = new WebSocket(SERVER, { headers: { Origin: ORIGIN } })
  let me = null, seq = 0, target = null, restUntil = 0
  const others = new Map()
  const timers = []
  const every = (ms, fn) => timers.push(setInterval(fn, ms))
  const later = (ms, fn) => timers.push(setTimeout(fn, ms))
  bot.send = (msg) => ws.readyState === 1 && ws.send(JSON.stringify(msg))

  bot.nick = nickFor(persona)
  ws.onopen = () => bot.send({ type: 'join', name: bot.nick, gender: persona.gender,
    token: tokens[persona.name]?.token, appearance: tokens[persona.name]?.appearance })
  ws.onmessage = (e) => {
    const m = JSON.parse(e.data)
    switch (m.type) {
      case 'welcome': {
        bot.selfId = m.selfId
        const self = m.players.find((p) => p.id === m.selfId)
        tokens[persona.name] = { nick: bot.nick, token: m.token, appearance: self.appearance }
        fs.writeFileSync(TOKENS, JSON.stringify(tokens, null, 2))
        for (const p of m.players) trackPlayer(p)
        restUntil = Date.now() + rand(1000, 6000)
        log(`${persona.name} (${bot.nick}) у грі (онлайн: ${m.players.length})`)
        later(rand(2000, 8000), () => bot.send({ type: 'status', text: pick(STATUSES) }))
        break
      }
      case 'chatHistory': {
        const wasGroup = bot.group
        bot.group = m.group
        bot.ready = true
        // Людина приєдналася до чату бота — бот вітає її в груповому чаті.
        if (m.group && m.group !== wasGroup) {
          later(800, () => {
            const mates = humansOnline().filter(([, p]) => p.group === m.group)
            if (mates.length) say(bot, pickFresh(bot, GENERIC.welcome), { name: mates.at(-1)[1].name }, rand(1500, 3500))
          })
        }
        break
      }
      case 'chat':
        if (bot.ready && m.group === bot.group) onChatMessage(m)
        break
      case 'joined':
        trackPlayer(m.player)
        break
      case 'left':
        others.delete(m.id)
        players.delete(m.id)
        break
      case 'playerUpdate': {
        const p = players.get(m.id)
        if (p) p.group = m.group
        break
      }
      case 'state':
        for (const p of m.players) {
          if (p.id === bot.selfId) me = p
          else others.set(p.id, { ...others.get(p.id), ...p })
        }
        break
      case 'handshakeOffer':
        if (m.to === bot.selfId && chance(0.85)) later(rand(600, 2000), () => bot.send({ type: 'handshake' }))
        break
    }
  }
  ws.onclose = () => {
    timers.forEach(clearTimeout)
    timers.forEach(clearInterval)
    bot.ready = false
    log(`${persona.name}: з'єднання закрито — перепідключення за 5–15 с`)
    setTimeout(() => runBot(persona), rand(5000, 15000))
  }
  ws.onerror = () => {}

  function trackPlayer(p) {
    players.set(p.id, { name: p.name, group: p.group })
  }

  // Рух: 20 кроків/с до цілі; дійшов — відпочинок (здебільшого коротко, інколи довго), потім нова ціль.
  every(50, () => {
    if (!me || Date.now() < restUntil) return
    if (!target) target = { x: rand(-AREA, AREA), z: rand(-AREA, AREA) }
    const dx = target.x - me.x, dz = target.z - me.z, d = Math.hypot(dx, dz)
    if (d < 0.4 || (me.moving === false && chance(0.01))) {
      target = null
      restUntil = Date.now() + (chance(0.2) ? rand(20000, 45000) : rand(3000, 15000))
      return
    }
    const speed = Math.min(1, d / 0.3) * (chance(0.15) ? 0.6 : 1)
    bot.send({ type: 'input', seq: ++seq, dx: (dx / d) * speed, dz: (dz / d) * speed })
  })
  every(4000, () => { if (me?.moving && chance(0.08)) bot.send({ type: 'jump' }) })
  every(60000, () => { if (chance(0.25)) bot.send({ type: 'status', text: pick(STATUSES) }) })
  // Рукостискання: якщо поруч хтось стоїть — інколи простягає руку.
  every(5000, () => {
    if (!me || chance(0.85)) return
    const near = [...others.values()].some((p) => p.x !== undefined && Math.hypot(p.x - me.x, p.z - me.z) < 1.6)
    if (near) bot.send({ type: 'handshake' })
  })
}

PERSONAS.forEach((p, i) => setTimeout(() => runBot(p), i * rand(800, 2500)))
setInterval(maybeStartDialogue, 20_000)
