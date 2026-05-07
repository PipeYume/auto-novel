import type { KyInstance } from 'ky';

import type {
  Page,
  WebNovelChapter,
  WebNovelListItem,
  WebNovelMetadata,
  WebNovelProvider,
} from '@/provider/types';

import { Alphapolis } from '@/provider/alphapolis';
import { Hameln } from '@/provider/hameln';
import { Kakuyomu } from '@/provider/kakuyomu';
import { Novelup } from '@/provider/novelup';
import { Pixiv } from '@/provider/pixiv';
import { Syosetu } from '@/provider/syosetu';

export { emptyPage, WebNovelAttention, WebNovelType } from '@/provider/types';
export type {
  Page,
  WebNovelAuthor,
  WebNovelChapter,
  WebNovelListItem,
  WebNovelMetadata,
  WebNovelTocItem,
  WebNovelProvider,
} from '@/provider/types';
export { Alphapolis, Hameln, Kakuyomu, Novelup, Pixiv, Syosetu };

type ProviderInitFn = (_: KyInstance) => WebNovelProvider;

type ProviderId =
  | 'alphapolis'
  | 'hameln'
  | 'kakuyomu'
  | 'novelup'
  | 'pixiv'
  | 'syosetu';

type ProviderRegistry = Record<ProviderId, ProviderInitFn>;

export class Crawler {
  readonly client: KyInstance;

  private readonly providers = new Map<string, ProviderInitFn>();
  private readonly providerInstances = new Map<string, WebNovelProvider>();

  constructor(
    client: KyInstance,
    initialProviders: ProviderRegistry = {
      alphapolis: (ky) => new Alphapolis(ky),
      hameln: (ky) => new Hameln(ky),
      pixiv: (ky) => new Pixiv(ky),
      novelup: (ky) => new Novelup(ky),
      kakuyomu: (ky) => new Kakuyomu(ky),
      syosetu: (ky) => new Syosetu(ky, { concurrency: 2 }),
    },
  ) {
    this.client = client;
    for (const [providerId, provider] of Object.entries(initialProviders)) {
      this.providers.set(providerId, provider);
    }
  }

  getProvider(providerId: string): WebNovelProvider {
    const cachedProvider = this.providerInstances.get(providerId);
    if (cachedProvider) {
      return cachedProvider;
    }

    const providerInit = this.providers.get(providerId);
    if (!providerInit) {
      throw new Error(`Unknown providerId: ${providerId}`);
    }

    const provider = providerInit(this.client);
    this.providerInstances.set(providerId, provider);
    return provider;
  }

  async getRank(
    providerId: string,
    options: Record<string, string>,
  ): Promise<Page<WebNovelListItem> | null | undefined> {
    return this.getProvider(providerId).getRank(options);
  }

  async getMetadata(
    providerId: string,
    novelId: string,
  ): Promise<WebNovelMetadata | null | undefined> {
    return this.getProvider(providerId).getMetadata(novelId);
  }

  async getChapter(
    providerId: string,
    novelId: string,
    chapterId: string,
  ): Promise<WebNovelChapter | null | undefined> {
    return this.getProvider(providerId).getChapter(novelId, chapterId);
  }
}
