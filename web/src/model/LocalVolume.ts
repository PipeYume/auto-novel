import type { Glossary } from './Glossary';

export interface ChapterTranslation {
  glossaryId: string;
  glossary: Glossary;
  paragraphs: string[];
}

export interface LocalVolumeMetadata {
  id: string;
  readAt?: number;
  createAt: number;
  toc: {
    chapterId: string;
    baidu?: string; // 保留旧本地数据兼容，避免历史百度翻译索引读取后丢失。
    youdao?: string;
    gpt?: string;
    sakura?: string;
  }[];
  glossaryId: string;
  glossary: Glossary;
  favoredId: string;
}

export interface LocalVolumeChapter {
  id: string;
  volumeId: string;
  paragraphs: string[];
  baidu?: ChapterTranslation; // 保留旧本地数据兼容，允许继续读取历史百度翻译内容。
  youdao?: ChapterTranslation;
  gpt?: ChapterTranslation;
  sakura?: ChapterTranslation;
}
