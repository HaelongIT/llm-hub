/**
 * 답변용 최소 마크다운 파서. 문자열 → 블록 노드 배열(순수 함수).
 *
 * LLM 답변이 실제로 내는 구조만 다룬다: 제목·문단·목록·펜스 코드·인용, 그리고 인라인의
 * 굵게(**)·기울임(*)·코드(`)·링크. 의존성은 없다(react-markdown 등 도입 없이 자체 구현).
 *
 * 안전: 링크 href는 http(s)만 허용하고 그 외 스킴은 리터럴 텍스트로 되돌린다(javascript: 등 차단).
 * 렌더러(Markdown.tsx)는 이 노드 트리로 React 엘리먼트를 만들 뿐 HTML을 주입하지 않는다.
 *
 * 밑줄(_)은 기울임으로 보지 않는다 — snake_case 식별자가 흔해 오인이 잦기 때문.
 */

export type InlineNode =
	| { type: 'text'; value: string }
	| { type: 'strong'; children: InlineNode[] }
	| { type: 'em'; children: InlineNode[] }
	| { type: 'code'; value: string }
	| { type: 'link'; href: string; children: InlineNode[] };

export type BlockNode =
	| { type: 'heading'; level: number; children: InlineNode[] }
	| { type: 'paragraph'; children: InlineNode[] }
	| { type: 'list'; ordered: boolean; items: InlineNode[][] }
	| { type: 'code'; value: string }
	| { type: 'blockquote'; children: InlineNode[] };

const RE_FENCE = /^```/;
const RE_HEADING = /^(#{1,6})\s+(.*)$/;
const RE_QUOTE = /^>\s?/;
const RE_UL = /^\s*[-*+]\s+/;
const RE_OL = /^\s*\d+\.\s+/;

function isBlockStart(line: string): boolean {
	return (
		RE_FENCE.test(line) ||
		RE_HEADING.test(line) ||
		RE_QUOTE.test(line) ||
		RE_UL.test(line) ||
		RE_OL.test(line)
	);
}

export function parseMarkdown(src: string): BlockNode[] {
	const lines = src.replace(/\r\n?/g, '\n').split('\n');
	const blocks: BlockNode[] = [];
	let i = 0;

	while (i < lines.length) {
		const line = lines[i];

		if (line.trim() === '') {
			i++;
			continue;
		}

		// 펜스 코드 — 내부는 인라인 파싱하지 않는다.
		if (RE_FENCE.test(line)) {
			const buf: string[] = [];
			i++;
			while (i < lines.length && !RE_FENCE.test(lines[i])) {
				buf.push(lines[i]);
				i++;
			}
			i++; // 닫는 ```
			blocks.push({ type: 'code', value: buf.join('\n') });
			continue;
		}

		const h = line.match(RE_HEADING);
		if (h) {
			blocks.push({ type: 'heading', level: h[1].length, children: parseInline(h[2].trim()) });
			i++;
			continue;
		}

		// 인용 — 연속된 > 줄을 한 블록으로.
		if (RE_QUOTE.test(line)) {
			const buf: string[] = [];
			while (i < lines.length && RE_QUOTE.test(lines[i])) {
				buf.push(lines[i].replace(RE_QUOTE, ''));
				i++;
			}
			blocks.push({ type: 'blockquote', children: parseInline(buf.join(' ').trim()) });
			continue;
		}

		if (RE_UL.test(line)) {
			const items: InlineNode[][] = [];
			while (i < lines.length && RE_UL.test(lines[i])) {
				items.push(parseInline(lines[i].replace(RE_UL, '').trim()));
				i++;
			}
			blocks.push({ type: 'list', ordered: false, items });
			continue;
		}

		if (RE_OL.test(line)) {
			const items: InlineNode[][] = [];
			while (i < lines.length && RE_OL.test(lines[i])) {
				items.push(parseInline(lines[i].replace(RE_OL, '').trim()));
				i++;
			}
			blocks.push({ type: 'list', ordered: true, items });
			continue;
		}

		// 문단 — 다음 빈 줄이나 다른 블록 시작 전까지 이어 붙인다.
		const buf: string[] = [];
		while (i < lines.length && lines[i].trim() !== '' && !isBlockStart(lines[i])) {
			buf.push(lines[i].trim());
			i++;
		}
		blocks.push({ type: 'paragraph', children: parseInline(buf.join(' ')) });
	}

	return blocks;
}

// 먼저 매치되는 것이 이긴다: 코드 → 링크 → 굵게 → 기울임. 굵게(**)가 기울임(*)보다 앞이라
// 같은 위치에서 굵게가 우선한다.
const INLINE_PATTERNS: { type: InlineNode['type']; re: RegExp }[] = [
	{ type: 'code', re: /`([^`]+)`/ },
	{ type: 'link', re: /\[([^\]]*)\]\(([^)\s]+)\)/ },
	{ type: 'strong', re: /\*\*([\s\S]+?)\*\*/ },
	{ type: 'em', re: /\*([\s\S]+?)\*/ },
];

function parseInline(text: string): InlineNode[] {
	if (text === '') return [];

	let best: { type: InlineNode['type']; m: RegExpExecArray } | null = null;
	for (const p of INLINE_PATTERNS) {
		const m = p.re.exec(text);
		if (m && (best === null || m.index < best.m.index)) best = { type: p.type, m };
	}
	if (!best) return [{ type: 'text', value: text }];

	const { type, m } = best;
	const nodes: InlineNode[] = [];
	const before = text.slice(0, m.index);
	const after = text.slice(m.index + m[0].length);
	if (before) nodes.push({ type: 'text', value: before });

	if (type === 'code') {
		nodes.push({ type: 'code', value: m[1] });
	} else if (type === 'link') {
		const href = sanitizeHref(m[2]);
		if (href) nodes.push({ type: 'link', href, children: parseInline(m[1]) });
		else nodes.push({ type: 'text', value: m[0] }); // 안전하지 않은 스킴 → 리터럴로
	} else if (type === 'strong') {
		nodes.push({ type: 'strong', children: parseInline(m[1]) });
	} else {
		nodes.push({ type: 'em', children: parseInline(m[1]) });
	}

	nodes.push(...parseInline(after));
	return nodes;
}

/** http(s)만 통과. 그 외(javascript:, data:, 상대경로 등)는 null → 링크를 만들지 않는다. */
function sanitizeHref(href: string): string | null {
	const trimmed = href.trim();
	return /^https?:\/\//i.test(trimmed) ? trimmed : null;
}
