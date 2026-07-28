/** @type {import('tailwindcss').Config} */
// Dark "ScreenPilot" theme tokens. Surfaces are near-black with a blue-violet
// cast; the brand accent is a violet -> fuchsia gradient. The CSS variables
// these mirror live in src/index.css (:root).
export default {
  content: ['./index.html', './src/**/*.{js,jsx}'],
  theme: {
    extend: {
      colors: {
        // surfaces — dark with a blue-violet cast, lifted for readability
        app: '#0D0D17',
        sidebar: '#111120',
        card: '#171729',
        'card-inner': '#121221',
        hover: 'rgba(255,255,255,0.06)',
        subtle: 'rgba(148,163,184,0.16)', // 1px borders
        // brand
        primary: {
          400: '#A78BFA',
          500: '#8B5CF6',
          600: '#7C3AED',
        },
        accent: '#C026D3', // fuchsia pole of the brand gradient
        // status
        success: { DEFAULT: '#22C55E', 400: '#4ADE80' },
        danger: { DEFAULT: '#F43F5E' },
        warning: { DEFAULT: '#F59E0B' },
        info: { DEFAULT: '#38BDF8' },
        // text — brighter secondary/muted tiers for small-size readability
        txt: {
          primary: '#F4F7FB',
          secondary: '#A9B7CC',
          muted: '#8090A8',
        },
      },
      fontFamily: {
        sans: ['Inter', 'system-ui', 'sans-serif'],
      },
      borderRadius: {
        card: '16px',
        tile: '12px',
        btn: '10px',
      },
      boxShadow: {
        'glow-primary': '0 0 24px rgba(139,92,246,0.35)',
        'glow-success': '0 0 8px rgba(74,222,128,0.6)',
        'glow-danger': '0 0 8px rgba(244,63,94,0.55)',
      },
      keyframes: {
        'fade-up': {
          '0%': { opacity: '0', transform: 'translateY(8px)' },
          '100%': { opacity: '1', transform: 'translateY(0)' },
        },
        'pop-in': {
          '0%': { opacity: '0', transform: 'scale(0.96) translateY(6px)' },
          '100%': { opacity: '1', transform: 'scale(1) translateY(0)' },
        },
        shimmer: {
          '0%': { backgroundPosition: '-400px 0' },
          '100%': { backgroundPosition: '400px 0' },
        },
        float: {
          '0%, 100%': { transform: 'translateY(0) scale(1)' },
          '50%': { transform: 'translateY(-14px) scale(1.04)' },
        },
      },
      animation: {
        'fade-up': 'fade-up 0.35s ease-out both',
        'pop-in': 'pop-in 0.22s ease-out both',
        shimmer: 'shimmer 1.6s linear infinite',
        float: 'float 7s ease-in-out infinite',
      },
    },
  },
  plugins: [],
}
