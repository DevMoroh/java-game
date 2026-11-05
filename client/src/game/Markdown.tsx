import type { ReactNode } from 'react'

/**
 * Мінімальний markdown для статей: абзаци, списки «- » / «1. », блоки коду ```, `код`, **жирний**.
 * Без HTML — лише React-вузли, тож текст статті не може вставити розмітку на сторінку.
 */
export function Markdown({ text }: { text: string }) {
  const blocks: ReactNode[] = []
  const lines = text.replace(/\r\n/g, '\n').split('\n')
  let i = 0
  while (i < lines.length) {
    const line = lines[i]
    if (line.startsWith('```')) {
      const code: string[] = []
      for (i++; i < lines.length && !lines[i].startsWith('```'); i++) code.push(lines[i])
      i++
      blocks.push(
        <pre key={blocks.length}>
          <code>{code.join('\n')}</code>
        </pre>,
      )
    } else if (/^\s*([-*]|\d+\.)\s/.test(line)) {
      const ordered = /^\s*\d+\./.test(line)
      const items: string[] = []
      for (; i < lines.length && /^\s*([-*]|\d+\.)\s/.test(lines[i]); i++) items.push(lines[i].replace(/^\s*([-*]|\d+\.)\s/, ''))
      const children = items.map((item, k) => <li key={k}>{inline(item)}</li>)
      blocks.push(ordered ? <ol key={blocks.length}>{children}</ol> : <ul key={blocks.length}>{children}</ul>)
    } else if (line.trim() === '') {
      i++
    } else {
      const para: string[] = []
      for (; i < lines.length && lines[i].trim() !== '' && !lines[i].startsWith('```') && !/^\s*([-*]|\d+\.)\s/.test(lines[i]); i++) {
        para.push(lines[i])
      }
      const heading = para.length === 1 && /^#{1,3}\s/.test(para[0])
      blocks.push(
        heading ? (
          <h4 key={blocks.length}>{inline(para[0].replace(/^#{1,3}\s/, ''))}</h4>
        ) : (
          <p key={blocks.length}>{inline(para.join(' '))}</p>
        ),
      )
    }
  }
  return <div className="markdown">{blocks}</div>
}

function inline(text: string): ReactNode[] {
  return text.split(/(`[^`]+`|\*\*[^*]+\*\*)/g).map((part, k) => {
    if (part.startsWith('`') && part.endsWith('`') && part.length > 1) return <code key={k}>{part.slice(1, -1)}</code>
    if (part.startsWith('**') && part.endsWith('**') && part.length > 3) return <strong key={k}>{part.slice(2, -2)}</strong>
    return part
  })
}
