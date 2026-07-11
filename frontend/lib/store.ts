import { create } from 'zustand';

/** 사이드바 세션 상태 (docs/01 CLIENT). 서버가 진실이고 이건 화면 상태다. */
type SessionState = {
	currentSessionId: string | null;
	/** 메인 헤더에 보일 현재 세션 제목. 목록에서 고를 때 함께 세팅한다. */
	currentTitle: string | null;
	select: (id: string | null, title?: string | null) => void;
};

export const useSessionStore = create<SessionState>((set) => ({
	currentSessionId: null,
	currentTitle: null,
	select: (id, title = null) => set({ currentSessionId: id, currentTitle: id ? title : null }),
}));
