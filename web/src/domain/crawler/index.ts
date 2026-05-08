import ky from 'ky';

import { WebNovelCrawler } from '@auto-novel/crawler';

const checkAddon = () => {
  return window.Addon !== undefined;
};

const getCrawler = () => {
  if (!window.Addon) return undefined;
  const client = ky.create({ fetch: window.Addon.fetch });
  return new WebNovelCrawler(client);
};

const updateWebNovel = async (providerId: string, novelId: string) => {
  const crawler = getCrawler();
  if (!crawler) throw new Error('Provider not available');

  const metadata = await crawler.getMetadata(providerId, novelId);
  if (metadata == null) throw new Error('Novel not found');

  console.log(metadata);
};

export const CrawlerService = {
  checkAddon,
  updateWebNovel,
};
