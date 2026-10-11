## First log into aws



## Then download the lasted repository




## Fix Nginx config if needed

```cmd
sudo vim /etc/nginx/sites-enabled/fastapi_nginx
```

then edit the config
```vim
server{
        listen 80;
        server_name 13.54.62.61;
        location / {
                proxy_pass http://127.0.0.1:8000;
                proxy_connect_timeout 300s;
                proxy_send_timeout 300s;
                proxy_read_timeout 300s;
                proxy_connect_timeout 300s;
        }
}
```

Then restart the nginx
```cmd
sudo service nginx restart
```

## Copy the ENV file 
Create a env file in the root folder and populate with the secrets. 
```env
GOOGLE_MAPS_API_KEY = ""
ENABLE_PARK_FALLBACK=false
OPENAI_API_KEY= ""
GIT_PERSONAL_ACCESS_TOKEN = ""
```
`ENABLE_PARK_FALLBACK` is disabled by default. Set it to `true` to allow
generic `park` Places results in addition to `city_park` and `garden` results.

make sure to add env to gitignore

## Run the FastAPI

```cmd
uvicorn main:app --host 127.0.0.1 --port 8000
```
