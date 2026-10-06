/** Parse the server tool kill switch. Tools stay enabled by default for backward compatibility. */
export function toolsEnabledFromEnv(value) {
	if (value == null || String(value).trim() === '') return true
	return !['0', 'false', 'off', 'no'].includes(String(value).trim().toLowerCase())
}
