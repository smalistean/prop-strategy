export default defineNuxtConfig({
  compatibilityDate: '2026-08-18',

  // Fully static: `nuxt generate` emits plain files for S3, which serves objects and cannot run a
  // server. ssr:false keeps the book a client-side fetch, so the page is never stale at build time -
  // it reflects whatever the ranker last wrote, not whatever was true when it was deployed.
  ssr: false,

  nitro: { preset: 'static' },

  runtimeConfig: {
    public: {
      // Injected at build time by scripts/deploy-web.sh from the stack's SignalApiUrl output, so the
      // page never carries a hardcoded endpoint that drifts from the deployed Lambda.
      signalApiUrl: process.env.NUXT_PUBLIC_SIGNAL_API_URL || ''
    }
  },

  app: {
    // S3 website hosting serves from the bucket root.
    baseURL: '/',
    head: {
      title: 'XVF signal',
      meta: [{ name: 'viewport', content: 'width=device-width, initial-scale=1' }]
    }
  }
})
