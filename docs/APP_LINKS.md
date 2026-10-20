# App Links (abrir el reset de contraseña en la app)

El correo de reset apunta a `https://<dominio>/reset-password?token=…&email=…`. Si el
dominio está verificado como App Link, Android abre la app en la pantalla “Nueva
contraseña” con el código y el email ya rellenados. Si no lo está, el enlace se abre en el
navegador (comportamiento anterior, sin errores).

## Configuración

1. **Huella del certificado** (una vez por publicación):
   - Play Console → *Test and release* → *Setup* → *App integrity* → *App signing key
     certificate* → **SHA-256**. Si usas Play App Signing, esta es la huella que hay que
     publicar (no la del keystore de subida).
   - Build local: `keytool -list -v -keystore <keystore> -alias <alias>` → línea SHA256.
2. **Backend** (`XyonWork`) del dominio, en el entorno:
   ```
   ANDROID_APP_CERT_SHA256="AB:CD:…:12:34"     # varias separadas por comas
   ANDROID_APP_PACKAGE="com.xyon.empleados"    # release; añade com.xyon.empleados.debug para probar
   ```
   El backend sirve `https://<dominio>/.well-known/assetlinks.json`. Sin huella devuelve
   404 y los enlaces se abren en el navegador.
3. **Manifest** (`app/src/main/AndroidManifest.xml`): el host verificado está declarado en
   el `intent-filter` con `android:autoVerify="true"` y `pathPrefix="/reset-password"`.
4. Instalar/actualizar la app (Android verifica al instalar; en Android 15+ también
   re-verifica en segundo plano).

## Comprobar

```bash
adb shell pm verify-app-links --re-verify com.xyon.empleados
adb shell pm get-app-links com.xyon.empleados      # el host debe salir "verified"
adb shell am start -a android.intent.action.VIEW \
  -d "https://work.xyoncloud.win/reset-password?token=TEST&email=test%40example.com"
```

## Limitaciones (importantes)

- **Un host por despliegue**: el manifest solo declara `work.xyoncloud.win`. Para otro
  dominio propio hay que añadir su `<data android:host="…">` y publicar una actualización
  de la app; el backend ya sirve `assetlinks.json` en cada instancia.
- **Comodines**: `*.dominio` exige publicar `assetlinks.json` en el **dominio raíz**
  (`https://dominio/.well-known/assetlinks.json`), no en cada subdominio. Además, en
  Android 11 y anteriores la verificación es todo o nada: si un host declarado falla,
  ninguno se verifica. No añadir comodines sin tener el raíz configurado.
- **Android 12+**: el usuario puede asociar manualmente un dominio no verificado en
  *Ajustes → Abrir por defecto*.
- El enlace **nunca** cambia el servidor configurado en la app: si el token pertenece a
  otra empresa, el reset fallará contra el servidor actual.
