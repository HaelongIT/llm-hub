import assert from 'node:assert/strict';
import { describe, it } from 'node:test';

import { parseMarkdown } from './markdown.ts';

describe('마크다운 블록', () => {
	it('제목', () => {
		assert.deepEqual(parseMarkdown('## 제목'), [
			{ type: 'heading', level: 2, children: [{ type: 'text', value: '제목' }] },
		]);
	});

	it('문단', () => {
		assert.deepEqual(parseMarkdown('그냥 문장'), [
			{ type: 'paragraph', children: [{ type: 'text', value: '그냥 문장' }] },
		]);
	});

	it('순서 없는 목록', () => {
		assert.deepEqual(parseMarkdown('- 하나\n- 둘'), [
			{
				type: 'list',
				ordered: false,
				items: [[{ type: 'text', value: '하나' }], [{ type: 'text', value: '둘' }]],
			},
		]);
	});

	it('순서 있는 목록', () => {
		const r = parseMarkdown('1. 하나\n2. 둘');
		assert.equal(r.length, 1);
		assert.equal(r[0].type, 'list');
		assert.equal((r[0] as { ordered: boolean }).ordered, true);
		assert.equal((r[0] as { items: unknown[] }).items.length, 2);
	});

	it('펜스 코드 블록은 인라인을 파싱하지 않는다', () => {
		assert.deepEqual(parseMarkdown('```\nconst x = **1**\n```'), [
			{ type: 'code', value: 'const x = **1**' },
		]);
	});

	it('인용', () => {
		assert.deepEqual(parseMarkdown('> 인용문'), [
			{ type: 'blockquote', children: [{ type: 'text', value: '인용문' }] },
		]);
	});

	it('빈 줄로 문단을 나눈다', () => {
		const r = parseMarkdown('첫째 문단\n\n둘째 문단');
		assert.equal(r.length, 2);
		assert.equal(r[0].type, 'paragraph');
		assert.equal(r[1].type, 'paragraph');
	});

	it('제목과 목록이 섞인 문서', () => {
		const r = parseMarkdown('## 요약\n- 항목1\n- 항목2');
		assert.equal(r.length, 2);
		assert.equal(r[0].type, 'heading');
		assert.equal(r[1].type, 'list');
	});
});

describe('마크다운 인라인', () => {
	it('굵게', () => {
		assert.deepEqual(parseMarkdown('**굵게**'), [
			{
				type: 'paragraph',
				children: [{ type: 'strong', children: [{ type: 'text', value: '굵게' }] }],
			},
		]);
	});

	it('기울임', () => {
		assert.deepEqual(parseMarkdown('*기울임*'), [
			{
				type: 'paragraph',
				children: [{ type: 'em', children: [{ type: 'text', value: '기울임' }] }],
			},
		]);
	});

	it('인라인 코드', () => {
		assert.deepEqual(parseMarkdown('`x = 1`'), [
			{ type: 'paragraph', children: [{ type: 'code', value: 'x = 1' }] },
		]);
	});

	it('텍스트 사이의 굵게', () => {
		assert.deepEqual(parseMarkdown('앞 **가운데** 뒤'), [
			{
				type: 'paragraph',
				children: [
					{ type: 'text', value: '앞 ' },
					{ type: 'strong', children: [{ type: 'text', value: '가운데' }] },
					{ type: 'text', value: ' 뒤' },
				],
			},
		]);
	});

	it('http 링크는 허용', () => {
		assert.deepEqual(parseMarkdown('[문서](https://e.com/a)'), [
			{
				type: 'paragraph',
				children: [
					{ type: 'link', href: 'https://e.com/a', children: [{ type: 'text', value: '문서' }] },
				],
			},
		]);
	});

	it('javascript: 링크는 새니타이즈해 리터럴 텍스트로 (XSS 차단)', () => {
		assert.deepEqual(parseMarkdown('[클릭](javascript:evil)'), [
			{ type: 'paragraph', children: [{ type: 'text', value: '[클릭](javascript:evil)' }] },
		]);
	});

	it('snake_case는 기울임으로 오인하지 않는다', () => {
		assert.deepEqual(parseMarkdown('user_name_field'), [
			{ type: 'paragraph', children: [{ type: 'text', value: 'user_name_field' }] },
		]);
	});
});
