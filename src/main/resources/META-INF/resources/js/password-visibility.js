export function configurePasswordVisibility(root = document) {
    const fields = [...root.querySelectorAll('[data-password-toggle]')].map(button => {
        const input = document.getElementById(button.getAttribute('aria-controls'));
        function hide() {
            input.type = 'password';
            render();
        }
        function render() {
            const visible = input.type === 'text';
            button.disabled = input.disabled;
            button.setAttribute('aria-pressed', String(visible));
            button.setAttribute('aria-label', visible ? 'Dölj lösenord' : 'Visa lösenord');
            button.title = visible ? 'Dölj lösenord' : 'Visa lösenord';
            button.querySelector('[data-password-show]').toggleAttribute('hidden', visible);
            button.querySelector('[data-password-hide]').toggleAttribute('hidden', !visible);
        }
        button.addEventListener('pointerdown', event => {
            // Keep the keyboard and caret when the icon is tapped while typing.
            if (document.activeElement === input) event.preventDefault();
        });
        button.addEventListener('click', () => {
            if (input.disabled) return;
            const keepFocus = document.activeElement === input;
            const start = input.selectionStart;
            const end = input.selectionEnd;
            input.type = input.type === 'password' ? 'text' : 'password';
            render();
            if (keepFocus) {
                input.focus({preventScroll: true});
                input.setSelectionRange(start, end);
            }
        });
        input.form?.addEventListener('submit', hide);
        render();
        return {hide, render};
    });
    return {
        hideAll: () => fields.forEach(field => field.hide()),
        refresh: () => fields.forEach(field => field.render())
    };
}
