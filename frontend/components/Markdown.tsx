'use client';

import type { ReactNode } from 'react';

import { type BlockNode, type InlineNode, parseMarkdown } from '@/lib/markdown';

// 답변 내부라 h1(페이지 제목)은 피한다: 마크다운 #→h2, ##→h3 … 로 한 단계 낮춘다.
const HEADING_TAGS = ['h2', 'h3', 'h4', 'h5', 'h6', 'h6'] as const;

function renderInline(nodes: InlineNode[]): ReactNode[] {
	return nodes.map((node, i) => {
		switch (node.type) {
			case 'text':
				return node.value;
			case 'strong':
				return <strong key={i}>{renderInline(node.children)}</strong>;
			case 'em':
				return <em key={i}>{renderInline(node.children)}</em>;
			case 'code':
				return <code key={i}>{node.value}</code>;
			case 'link':
				return (
					<a key={i} href={node.href} target="_blank" rel="noopener noreferrer">
						{renderInline(node.children)}
					</a>
				);
		}
	});
}

function renderBlock(node: BlockNode, key: number): ReactNode {
	switch (node.type) {
		case 'heading': {
			const Tag = HEADING_TAGS[Math.min(node.level, 6) - 1];
			return <Tag key={key}>{renderInline(node.children)}</Tag>;
		}
		case 'paragraph':
			return <p key={key}>{renderInline(node.children)}</p>;
		case 'blockquote':
			return <blockquote key={key}>{renderInline(node.children)}</blockquote>;
		case 'code':
			return (
				<pre key={key}>
					<code>{node.value}</code>
				</pre>
			);
		case 'list':
			return node.ordered ? (
				<ol key={key}>
					{node.items.map((item, j) => (
						<li key={j}>{renderInline(item)}</li>
					))}
				</ol>
			) : (
				<ul key={key}>
					{node.items.map((item, j) => (
						<li key={j}>{renderInline(item)}</li>
					))}
				</ul>
			);
	}
}

/** 완료된 답변을 열람실 결의 독서 타이포로 렌더한다. 스트리밍 중에는 쓰지 않는다(미완성 마크다운 깜빡임 방지). */
export function Markdown({ text }: { text: string }) {
	const blocks = parseMarkdown(text);
	return <div className="prose">{blocks.map((block, i) => renderBlock(block, i))}</div>;
}
