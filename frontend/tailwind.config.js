/** @type {import('tailwindcss').Config} */
export default {
  content: ['./index.html', './src/**/*.{js,jsx}'],
  theme: {
    extend: {
      colors: {
        marigold: {
          DEFAULT: '#F6A821',
          50: '#FEF6E7',
          100: '#FDEBC7',
          200: '#FBD88E',
          300: '#F9C456',
          400: '#F7B63B',
          500: '#F6A821',
          600: '#D88C09',
          700: '#A96D07',
          800: '#7A4F05',
          900: '#4B3003',
        },
        ink: {
          DEFAULT: '#16233F',
          50: '#EEF1F7',
          100: '#D5DCEA',
          200: '#A9B7D2',
          300: '#7E92BA',
          400: '#56709F',
          500: '#3D5378',
          600: '#2C3E5C',
          700: '#212F48',
          800: '#16233F',
          900: '#0D1526',
        },
        cream: '#FAF8F4',
        success: {
          DEFAULT: '#22C55E',
          100: '#DCFCE7',
          700: '#15803D',
        },
        danger: {
          DEFAULT: '#EF4444',
          100: '#FEE2E2',
          700: '#B91C1C',
        },
        warning: {
          DEFAULT: '#F59E0B',
          100: '#FEF3C7',
          700: '#B45309',
        },
      },
      fontFamily: {
        sans: ['Inter', 'system-ui', 'sans-serif'],
      },
      boxShadow: {
        card: '0 1px 2px rgba(22, 35, 63, 0.05), 0 6px 20px -4px rgba(22, 35, 63, 0.08)',
        'card-hover': '0 2px 4px rgba(22, 35, 63, 0.06), 0 14px 34px -8px rgba(22, 35, 63, 0.16)',
        'glow-marigold': '0 0 0 1px rgba(246, 168, 33, 0.35), 0 4px 18px -2px rgba(246, 168, 33, 0.45)',
        'glow-success': '0 0 8px rgba(34, 197, 94, 0.65)',
        'glow-danger': '0 0 8px rgba(239, 68, 68, 0.55)',
      },
      keyframes: {
        // content slides up + fades in as pages mount
        'fade-up': {
          '0%': { opacity: '0', transform: 'translateY(8px)' },
          '100%': { opacity: '1', transform: 'translateY(0)' },
        },
        // modal entrance: slight zoom + fade
        'pop-in': {
          '0%': { opacity: '0', transform: 'scale(0.96) translateY(6px)' },
          '100%': { opacity: '1', transform: 'scale(1) translateY(0)' },
        },
        // moving highlight for loading skeletons
        shimmer: {
          '0%': { backgroundPosition: '-400px 0' },
          '100%': { backgroundPosition: '400px 0' },
        },
        // slow drifting glow blobs on the login/pairing screens
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
